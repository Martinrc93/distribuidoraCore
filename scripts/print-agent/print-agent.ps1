#Requires -Version 5.1
<#
.SYNOPSIS
  Servicio local de impresion directa para Distribuidora en Windows.
.DESCRIPTION
  Escucha solo en http://127.0.0.1:47891/ para listar impresoras instaladas en
  esta PC e imprimir PDFs A4 o Ticket 80 mm de forma directa sin dialogo.
#>
param(
  [int]$Port = 47891
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$null = [Windows.Data.Pdf.PdfDocument, Windows.Data.Pdf, ContentType=WindowsRuntime]
$null = [Windows.Data.Pdf.PdfPageRenderOptions, Windows.Data.Pdf, ContentType=WindowsRuntime]
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime]
$null = [System.IO.WindowsRuntimeStreamExtensions]

$AsTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and
  $_.GetParameters().Count -eq 1 -and
  $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

$AsTaskAction = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and
  $_.GetParameters().Count -eq 1 -and
  $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction'
})[0]

$MaxPdfBytes = 20 * 1024 * 1024
$AgentVersion = '1.0.0'

function Await-WinRtOperation {
  param($Operation, [Type]$ResultType)
  $task = $AsTaskGeneric.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
  return $task.GetAwaiter().GetResult()
}

function Await-WinRtAction {
  param($Action)
  $task = $AsTaskAction.Invoke($null, @($Action))
  $null = $task.GetAwaiter().GetResult()
}

function Get-InstalledPrinters {
  $defaultName = ''
  try {
    $settings = New-Object System.Drawing.Printing.PrinterSettings
    if ($settings.IsValid) {
      $defaultName = [string]$settings.PrinterName
    }
  } catch {
    $defaultName = ''
  }

  $list = New-Object System.Collections.Generic.List[object]
  foreach ($printerName in [System.Drawing.Printing.PrinterSettings]::InstalledPrinters) {
    $name = [string]$printerName
    $list.Add([ordered]@{
      name      = $name
      isDefault = ($name -eq $defaultName)
    })
  }

  return [ordered]@{
    defaultPrinter = $defaultName
    printers       = @($list.ToArray())
  }
}

function Resolve-InstalledPrinter {
  param([string]$RequestedName)
  if ([string]::IsNullOrWhiteSpace($RequestedName)) { return $null }
  foreach ($printerName in [System.Drawing.Printing.PrinterSettings]::InstalledPrinters) {
    if ([string]$printerName -eq $RequestedName) {
      return [string]$printerName
    }
  }
  return $null
}

function Write-JsonResponse {
  param(
    [System.Net.HttpListenerResponse]$Response,
    [int]$StatusCode,
    [object]$Payload
  )
  $json = $Payload | ConvertTo-Json -Depth 6 -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  $Response.StatusCode = $StatusCode
  $Response.ContentType = 'application/json; charset=utf-8'
  $Response.ContentLength64 = $bytes.Length
  $Response.OutputStream.Write($bytes, 0, $bytes.Length)
  $Response.OutputStream.Close()
}

function Add-CorsHeaders {
  param(
    [System.Net.HttpListenerRequest]$Request,
    [System.Net.HttpListenerResponse]$Response
  )
  $origin = $Request.Headers['Origin']
  if (-not [string]::IsNullOrWhiteSpace($origin)) {
    $Response.Headers['Access-Control-Allow-Origin'] = $origin
    $Response.Headers['Vary'] = 'Origin'
  }
  $Response.Headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
  $Response.Headers['Access-Control-Allow-Headers'] = 'Content-Type'
  $Response.Headers['Access-Control-Allow-Private-Network'] = 'true'
  $Response.Headers['Access-Control-Max-Age'] = '600'
  $Response.Headers['Cache-Control'] = 'no-store'
}

function Read-RequestBytes {
  param([System.Net.HttpListenerRequest]$Request)
  $memory = New-Object System.IO.MemoryStream
  try {
    $buffer = New-Object byte[] 65536
    $total = 0
    while ($true) {
      $read = $Request.InputStream.Read($buffer, 0, $buffer.Length)
      if ($read -le 0) { break }
      $total += $read
      if ($total -gt $MaxPdfBytes) {
        throw [System.InvalidOperationException]::new('El archivo PDF supera el limite de 20 MB.')
      }
      $memory.Write($buffer, 0, $read)
    }
    return $memory.ToArray()
  } finally {
    $memory.Dispose()
  }
}

function Render-PdfPages {
  param(
    [string]$PdfPath,
    [string]$Format
  )
  $dpi = if ($Format -eq 'ticket') { 203.0 } else { 300.0 }
  $maxDimension = if ($Format -eq 'ticket') { 12000 } else { 4200 }

  $storageFile = Await-WinRtOperation ([Windows.Storage.StorageFile]::GetFileFromPathAsync($PdfPath)) ([Windows.Storage.StorageFile])
  $pdfDoc = Await-WinRtOperation ([Windows.Data.Pdf.PdfDocument]::LoadFromFileAsync($storageFile)) ([Windows.Data.Pdf.PdfDocument])
  $pageCount = [int]$pdfDoc.PageCount
  if ($pageCount -le 0) {
    throw [System.InvalidOperationException]::new('El PDF no contiene paginas.')
  }

  $rendered = New-Object System.Collections.Generic.List[object]
  try {
    for ($index = 0; $index -lt $pageCount; $index++) {
      $pdfPage = $pdfDoc.GetPage([uint32]$index)
      $winStream = $null
      $netStream = $null
      $tempImage = $null
      try {
        $size = $pdfPage.Size
        $scale = $dpi / 96.0
        $targetWidth = [int][Math]::Max(1, [Math]::Round($size.Width * $scale))
        $targetHeight = [int][Math]::Max(1, [Math]::Round($size.Height * $scale))
        if ($targetWidth -gt $maxDimension -or $targetHeight -gt $maxDimension) {
          $cap = [Math]::Min($maxDimension / [double]$targetWidth, $maxDimension / [double]$targetHeight)
          $targetWidth = [int][Math]::Max(1, [Math]::Round($targetWidth * $cap))
          $targetHeight = [int][Math]::Max(1, [Math]::Round($targetHeight * $cap))
        }

        $winStream = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream
        $options = New-Object Windows.Data.Pdf.PdfPageRenderOptions
        $options.DestinationWidth = [uint32]$targetWidth
        $options.DestinationHeight = [uint32]$targetHeight
        Await-WinRtAction ($pdfPage.RenderToStreamAsync($winStream, $options))

        $netStream = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($winStream)
        $netStream.Position = 0
        $tempImage = [System.Drawing.Image]::FromStream($netStream)
        $bitmap = New-Object System.Drawing.Bitmap($tempImage)
        $rendered.Add([pscustomobject]@{
          Bitmap        = $bitmap
          WidthInches   = [double]$size.Width / 96.0
          HeightInches  = [double]$size.Height / 96.0
        })
      } finally {
        if ($tempImage) { $tempImage.Dispose() }
        if ($netStream) { $netStream.Dispose() }
        if ($winStream) { $winStream.Dispose() }
        if ($pdfPage) { $pdfPage.Dispose() }
      }
    }
    return $rendered
  } catch {
    foreach ($item in $rendered) {
      if ($item.Bitmap) { $item.Bitmap.Dispose() }
    }
    throw
  }
}

function Send-PdfToPrinter {
  param(
    [byte[]]$PdfBytes,
    [string]$PrinterName,
    [string]$Format,
    [string]$JobName
  )
  $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) 'distribuidora-print-agent'
  if (-not (Test-Path -LiteralPath $tempDir)) {
    $null = New-Item -ItemType Directory -Path $tempDir -Force
  }
  $tempPdf = Join-Path $tempDir ("job-" + [Guid]::NewGuid().ToString('N') + '.pdf')
  $pages = $null
  $printDoc = $null
  try {
    [System.IO.File]::WriteAllBytes($tempPdf, $PdfBytes)
    $pages = Render-PdfPages -PdfPath $tempPdf -Format $Format

    $printDoc = New-Object System.Drawing.Printing.PrintDocument
    $printDoc.PrinterSettings.PrinterName = $PrinterName
    if (-not $printDoc.PrinterSettings.IsValid) {
      throw [System.InvalidOperationException]::new("La impresora '$PrinterName' no es valida.")
    }
    $printDoc.DocumentName = if ([string]::IsNullOrWhiteSpace($JobName)) { "Distribuidora ($Format)" } else { $JobName }
    $printDoc.PrintController = New-Object System.Drawing.Printing.StandardPrintController
    $printDoc.OriginAtMargins = $false
    $printDoc.DefaultPageSettings.Margins = New-Object System.Drawing.Printing.Margins(0, 0, 0, 0)

    $state = [pscustomobject]@{
      Index  = 0
      Pages  = $pages
      Format = $Format
    }

    $printDoc.add_QueryPageSettings({
      param($sender, $e)
      $page = $state.Pages[$state.Index]
      $e.PageSettings.Margins = New-Object System.Drawing.Printing.Margins(0, 0, 0, 0)
      if ($state.Format -eq 'ticket') {
        $widthHundredths = [int][Math]::Max(100, [Math]::Round($page.WidthInches * 100.0))
        $heightHundredths = [int][Math]::Max(100, [Math]::Round($page.HeightInches * 100.0))
        $e.PageSettings.PaperSize = New-Object System.Drawing.Printing.PaperSize('Ticket80mm', $widthHundredths, $heightHundredths)
      }
    })

    $printDoc.add_PrintPage({
      param($sender, $e)
      $page = $state.Pages[$state.Index]
      $bitmap = $page.Bitmap
      $bounds = $e.PageBounds

      $e.Graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $e.Graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
      $e.Graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

      $scaleX = [double]$bounds.Width / [double]$bitmap.Width
      $scaleY = [double]$bounds.Height / [double]$bitmap.Height
      $scale = [Math]::Min($scaleX, $scaleY)
      $drawWidth = [float]($bitmap.Width * $scale)
      $drawHeight = [float]($bitmap.Height * $scale)

      if ($state.Format -eq 'ticket') {
        $x = [float]$bounds.Left
        $y = [float]$bounds.Top
      } else {
        $x = [float]($bounds.Left + ($bounds.Width - $drawWidth) / 2.0)
        $y = [float]($bounds.Top + ($bounds.Height - $drawHeight) / 2.0)
      }

      $e.Graphics.DrawImage($bitmap, $x, $y, $drawWidth, $drawHeight)
      $state.Index++
      $e.HasMorePages = ($state.Index -lt $state.Pages.Count)
    })

    $printDoc.Print()
    return $pages.Count
  } finally {
    if ($printDoc) { $printDoc.Dispose() }
    if ($pages) {
      foreach ($item in $pages) {
        if ($item.Bitmap) { $item.Bitmap.Dispose() }
      }
    }
    if (Test-Path -LiteralPath $tempPdf) {
      Remove-Item -LiteralPath $tempPdf -Force -ErrorAction SilentlyContinue
    }
  }
}

function Send-TestPage {
  param(
    [string]$PrinterName,
    [string]$Format
  )
  $printDoc = New-Object System.Drawing.Printing.PrintDocument
  try {
    $printDoc.PrinterSettings.PrinterName = $PrinterName
    if (-not $printDoc.PrinterSettings.IsValid) {
      throw [System.InvalidOperationException]::new("La impresora '$PrinterName' no es valida.")
    }
    $printDoc.DocumentName = "Distribuidora - Prueba ($Format)"
    $printDoc.PrintController = New-Object System.Drawing.Printing.StandardPrintController
    $printDoc.OriginAtMargins = $false
    $printDoc.DefaultPageSettings.Margins = New-Object System.Drawing.Printing.Margins(0, 0, 0, 0)
    if ($Format -eq 'ticket') {
      $printDoc.DefaultPageSettings.PaperSize = New-Object System.Drawing.Printing.PaperSize('Ticket80mm', 315, 220)
    }

    $testInfo = [pscustomobject]@{
      PrinterName = $PrinterName
      FormatLabel = if ($Format -eq 'ticket') { 'Ticket 80 mm' } else { 'A4' }
      IsTicket    = ($Format -eq 'ticket')
      Timestamp   = (Get-Date).ToString('dd/MM/yyyy HH:mm:ss')
    }

    $printDoc.add_PrintPage({
      param($sender, $e)
      $fontFamily = if ($testInfo.IsTicket) { 'Courier New' } else { 'Segoe UI' }
      $titleSize = if ($testInfo.IsTicket) { 10 } else { 14 }
      $bodySize = if ($testInfo.IsTicket) { 8.5 } else { 10.5 }
      $margin = if ($testInfo.IsTicket) { 12 } else { 48 }

      $titleFont = New-Object System.Drawing.Font($fontFamily, $titleSize, [System.Drawing.FontStyle]::Bold)
      $bodyFont = New-Object System.Drawing.Font($fontFamily, $bodySize, [System.Drawing.FontStyle]::Regular)
      try {
        $rect = New-Object System.Drawing.RectangleF(
          [float]($e.PageBounds.Left + $margin),
          [float]($e.PageBounds.Top + $margin),
          [float][Math]::Max(100, $e.PageBounds.Width - 2 * $margin),
          [float][Math]::Max(80, $e.PageBounds.Height - 2 * $margin)
        )
        $e.Graphics.DrawString('Distribuidora - Prueba de impresion', $titleFont, [System.Drawing.Brushes]::Black, $rect)
        $bodyRect = New-Object System.Drawing.RectangleF($rect.X, $rect.Y + ($titleSize * 2.2), $rect.Width, $rect.Height)
        $lines = @(
          "Formato: $($testInfo.FormatLabel)",
          "Impresora: $($testInfo.PrinterName)",
          "Fecha: $($testInfo.Timestamp)",
          '',
          'La impresion directa desde el navegador esta configurada correctamente.'
        ) -join [Environment]::NewLine
        $e.Graphics.DrawString($lines, $bodyFont, [System.Drawing.Brushes]::Black, $bodyRect)
        $e.HasMorePages = $false
      } finally {
        $titleFont.Dispose()
        $bodyFont.Dispose()
      }
    })

    $printDoc.Print()
  } finally {
    $printDoc.Dispose()
  }
}

$createdNew = $false
$mutex = New-Object System.Threading.Mutex($true, "Local\DistribuidoraPrintAgent-$Port", [ref]$createdNew)
if (-not $createdNew) {
  Write-Host "El servicio de impresion local ya esta ejecutandose en http://127.0.0.1:$Port/."
  exit 0
}

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://127.0.0.1:$Port/")

try {
  $listener.Start()
  Write-Host "Servicio de impresion local de Distribuidora escuchando en http://127.0.0.1:$Port/"
  Write-Host 'Presiona Ctrl+C para detenerlo.'

  while ($listener.IsListening) {
    try {
      $context = $listener.GetContext()
    } catch [System.Net.HttpListenerException] {
      break
    } catch [System.ObjectDisposedException] {
      break
    }

    $request = $context.Request
    $response = $context.Response
    try {
      Add-CorsHeaders -Request $request -Response $response

      if ($request.HttpMethod -eq 'OPTIONS') {
        $response.StatusCode = 204
        $response.Close()
        continue
      }

      $path = $request.Url.AbsolutePath.TrimEnd('/')
      if ([string]::IsNullOrEmpty($path)) { $path = '/' }

      if ($request.HttpMethod -eq 'GET' -and ($path -eq '/health' -or $path -eq '/')) {
        Write-JsonResponse -Response $response -StatusCode 200 -Payload ([ordered]@{
          ok      = $true
          version = $AgentVersion
          engine  = 'windows-pdf'
        })
        continue
      }

      if ($request.HttpMethod -eq 'GET' -and $path -eq '/printers') {
        Write-JsonResponse -Response $response -StatusCode 200 -Payload (Get-InstalledPrinters)
        continue
      }

      if ($request.HttpMethod -eq 'POST' -and $path -eq '/print') {
        $requestedPrinter = [string]$request.QueryString['printer']
        $format = ([string]$request.QueryString['format']).ToLowerInvariant()
        $jobName = [string]$request.QueryString['jobName']

        if ([string]::IsNullOrWhiteSpace($format)) { $format = 'a4' }
        if ($format -ne 'a4' -and $format -ne 'ticket') {
          Write-JsonResponse -Response $response -StatusCode 400 -Payload ([ordered]@{ error = 'Formato de impresion invalido. Usa a4 o ticket.' })
          continue
        }

        $printerName = Resolve-InstalledPrinter -RequestedName $requestedPrinter
        if (-not $printerName) {
          Write-JsonResponse -Response $response -StatusCode 404 -Payload ([ordered]@{ error = "La impresora '$requestedPrinter' no esta instalada en esta PC." })
          continue
        }

        $pdfBytes = Read-RequestBytes -Request $request
        if ($pdfBytes.Length -lt 5 -or [System.Text.Encoding]::ASCII.GetString($pdfBytes, 0, 5) -ne '%PDF-') {
          Write-JsonResponse -Response $response -StatusCode 400 -Payload ([ordered]@{ error = 'El contenido enviado no es un PDF valido.' })
          continue
        }

        $pageCount = Send-PdfToPrinter -PdfBytes $pdfBytes -PrinterName $printerName -Format $format -JobName $jobName
        Write-JsonResponse -Response $response -StatusCode 200 -Payload ([ordered]@{
          ok      = $true
          printer = $printerName
          format  = $format
          pages   = $pageCount
        })
        continue
      }

      if ($request.HttpMethod -eq 'POST' -and $path -eq '/test') {
        $requestedPrinter = [string]$request.QueryString['printer']
        $format = ([string]$request.QueryString['format']).ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($format)) { $format = 'a4' }
        if ($format -ne 'a4' -and $format -ne 'ticket') {
          Write-JsonResponse -Response $response -StatusCode 400 -Payload ([ordered]@{ error = 'Formato de impresion invalido. Usa a4 o ticket.' })
          continue
        }

        $printerName = Resolve-InstalledPrinter -RequestedName $requestedPrinter
        if (-not $printerName) {
          Write-JsonResponse -Response $response -StatusCode 404 -Payload ([ordered]@{ error = "La impresora '$requestedPrinter' no esta instalada en esta PC." })
          continue
        }

        Send-TestPage -PrinterName $printerName -Format $format
        Write-JsonResponse -Response $response -StatusCode 200 -Payload ([ordered]@{
          ok      = $true
          printer = $printerName
          format  = $format
        })
        continue
      }

      Write-JsonResponse -Response $response -StatusCode 404 -Payload ([ordered]@{ error = 'Ruta no encontrada.' })
    } catch {
      try {
        Write-JsonResponse -Response $response -StatusCode 500 -Payload ([ordered]@{
          error = $_.Exception.Message
        })
      } catch {
        # Client disconnected before reading error response.
      }
    }
  }
} finally {
  if ($listener.IsListening) { $listener.Stop() }
  $listener.Close()
  $mutex.ReleaseMutex()
  $mutex.Dispose()
}
