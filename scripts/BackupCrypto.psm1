#Requires -Version 7.2

function Get-BackupKeyMaterial {
    $keyText = $env:BACKUP_ENCRYPTION_KEY
    if ([string]::IsNullOrWhiteSpace($keyText)) {
        $storePath = if ($env:BACKUP_KEY_STORE_PATH) {
            $env:BACKUP_KEY_STORE_PATH
        } else {
            Join-Path $env:LOCALAPPDATA 'Distribuidora\postgres-backup-key.dpapi'
        }
        if (-not (Test-Path -LiteralPath $storePath -PathType Leaf)) {
            throw "No existe la clave DPAPI de backup en $storePath. Ejecute scripts/initialize-backup-key.ps1 con la misma cuenta Windows."
        }
        try {
            $protected = Get-Content -LiteralPath $storePath -Raw
            $secure = ConvertTo-SecureString -String $protected
            $keyText = [Net.NetworkCredential]::new('', $secure).Password
        } catch {
            throw "No se pudo desbloquear la clave DPAPI de backup para la cuenta Windows actual: $($_.Exception.Message)"
        }
    }
    try { $material = [Convert]::FromBase64String($keyText) }
    catch { throw "BACKUP_ENCRYPTION_KEY no es Base64 válido." }
    if ($material.Length -ne 64) { throw "BACKUP_ENCRYPTION_KEY debe contener exactamente 64 bytes." }
    return ,$material
}

function Initialize-BackupEncryptionKeyStore {
    param([string]$StorePath = (Join-Path $env:LOCALAPPDATA 'Distribuidora\postgres-backup-key.dpapi'))
    if (Test-Path -LiteralPath $StorePath) {
        throw "Ya existe un almacén de clave en $StorePath; no se reemplazará automáticamente."
    }
    $directory = Split-Path -Parent $StorePath
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    foreach ($path in @($directory)) {
        $acl = Get-Acl -LiteralPath $path
        $acl.SetAccessRuleProtection($true, $false)
        $acl.SetOwner($identity)
        $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
        $propagation = [Security.AccessControl.PropagationFlags]::None
        $allow = [Security.AccessControl.AccessControlType]::Allow
        $acl.SetAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', $inheritance, $propagation, $allow))
        $acl.SetAccessRule([Security.AccessControl.FileSystemAccessRule]::new($system, 'FullControl', $inheritance, $propagation, $allow))
        Set-Acl -LiteralPath $path -AclObject $acl
    }
    $material = [Security.Cryptography.RandomNumberGenerator]::GetBytes(64)
    $keyText = [Convert]::ToBase64String($material)
    $secure = ConvertTo-SecureString -String $keyText -AsPlainText -Force
    $protected = ConvertFrom-SecureString -SecureString $secure
    Set-Content -LiteralPath $StorePath -Value $protected -NoNewline -Encoding ASCII
    $fileAcl = Get-Acl -LiteralPath $StorePath
    $fileAcl.SetAccessRuleProtection($true, $false)
    $fileAcl.SetOwner($identity)
    $fileAcl.SetAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', $allow))
    $fileAcl.SetAccessRule([Security.AccessControl.FileSystemAccessRule]::new($system, 'FullControl', $allow))
    Set-Acl -LiteralPath $StorePath -AclObject $fileAcl
    $sha = [Security.Cryptography.SHA256]::HashData($material)
    [pscustomobject]@{ Path = $StorePath; KeyId = [Convert]::ToHexString($sha[0..7]).ToLowerInvariant() }
}

function Write-BackupArchive {
    param([string]$OutputPath, [scriptblock]$WritePlaintext)
    $material = Get-BackupKeyMaterial
    [byte[]]$encryptionKey = $material[0..31]
    [byte[]]$macKey = $material[32..63]
    [byte[]]$header = [Text.Encoding]::ASCII.GetBytes('DCOREBK1')
    [byte[]]$iv = [Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
    $temporary = "$OutputPath.$([guid]::NewGuid().ToString('N')).tmp"
    $aes = [Security.Cryptography.Aes]::Create()
    try {
        $aes.Mode = [Security.Cryptography.CipherMode]::CBC
        $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
        $aes.Key = $encryptionKey
        $aes.IV = $iv
        $destination = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew,
            [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        try {
            $destination.Write($header, 0, $header.Length)
            $destination.Write($iv, 0, $iv.Length)
            $crypto = [Security.Cryptography.CryptoStream]::new(
                $destination, $aes.CreateEncryptor(), [Security.Cryptography.CryptoStreamMode]::Write, $true)
            try {
                & $WritePlaintext $crypto
                $crypto.FlushFinalBlock()
            } finally { $crypto.Dispose() }
            if ($destination.Length -le ($header.Length + $iv.Length + 16)) {
                throw 'No se produjo un archivo de backup válido.'
            }
            $destination.Position = 0
            $hmac = [Security.Cryptography.HMACSHA256]::new($macKey)
            try { [byte[]]$tag = $hmac.ComputeHash($destination) }
            finally { $hmac.Dispose() }
            $destination.Write($tag, 0, $tag.Length)
            $destination.Flush($true)
        } finally { $destination.Dispose() }
        [IO.File]::Move($temporary, $OutputPath, $true)
    } finally {
        $aes.Dispose()
        [IO.File]::Delete($temporary)
    }
}

function Protect-BackupArchive {
    param([Parameter(Mandatory)][string]$InputPath, [Parameter(Mandatory)][string]$OutputPath)
    $source = [IO.File]::OpenRead($InputPath)
    try {
        Write-BackupArchive -OutputPath $OutputPath -WritePlaintext { param($stream) $source.CopyTo($stream) }
    } finally { $source.Dispose() }
}

function Protect-BackupProcessOutput {
    param([Parameter(Mandatory)][Diagnostics.ProcessStartInfo]$StartInfo,
          [Parameter(Mandatory)][string]$OutputPath)
    $process = [Diagnostics.Process]::Start($StartInfo)
    try {
        $stderrTask = $process.StandardError.ReadToEndAsync()
        Write-BackupArchive -OutputPath $OutputPath -WritePlaintext {
            param($stream)
            $process.StandardOutput.BaseStream.CopyTo($stream)
            $process.WaitForExit()
            $stderr = $stderrTask.GetAwaiter().GetResult()
            if ($process.ExitCode -ne 0) {
                throw "pg_dump falló (código $($process.ExitCode)): $stderr"
            }
        }
    } finally {
        try {
            if (-not $process.HasExited) {
                $process.Kill()
                $process.WaitForExit()
            }
        } finally { $process.Dispose() }
    }
}

function Read-VerifiedBackupArchive {
    param([IO.FileStream]$Archive)
    $material = Get-BackupKeyMaterial
    [byte[]]$encryptionKey = $material[0..31]
    [byte[]]$macKey = $material[32..63]
    [byte[]]$expectedHeader = [Text.Encoding]::ASCII.GetBytes('DCOREBK1')
    $archive.Position = 0
    $headerLength = $expectedHeader.Length
    $tagLength = 32
    if ($archive.Length -le ($headerLength + 16 + $tagLength)) { throw 'El archivo de backup está truncado.' }
    [byte[]]$header = [byte[]]::new($headerLength)
    [byte[]]$iv = [byte[]]::new(16)
    if ($archive.Read($header, 0, $header.Length) -ne $header.Length -or
        -not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals($header, $expectedHeader)) {
        throw 'El formato del backup no es válido.'
    }
    if ($archive.Read($iv, 0, $iv.Length) -ne $iv.Length) { throw 'El backup no contiene un vector de inicialización válido.' }
    $authenticatedLength = $archive.Length - $tagLength
    $archive.Position = 0
    $hmac = [Security.Cryptography.HMACSHA256]::new($macKey)
    [byte[]]$buffer = [byte[]]::new(65536)
    $remaining = $authenticatedLength
    try {
        while ($remaining -gt 0) {
            $read = $archive.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
            if ($read -le 0) { throw 'El backup está truncado.' }
            $null = $hmac.TransformBlock($buffer, 0, $read, $buffer, 0)
            $remaining -= $read
        }
        [byte[]]$empty = [byte[]]::new(0)
        $null = $hmac.TransformFinalBlock($empty, 0, 0)
        [byte[]]$actualTag = $hmac.Hash
    } finally { $hmac.Dispose() }
    $archive.Position = $authenticatedLength
    [byte[]]$expectedTag = [byte[]]::new($tagLength)
    if ($archive.Read($expectedTag, 0, $tagLength) -ne $tagLength -or
        -not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals($actualTag, $expectedTag)) {
        throw 'La autenticación del backup falló; no se intentará restaurar.'
    }
    return [pscustomobject]@{
        EncryptionKey = $encryptionKey
        Iv = $iv
        CipherOffset = $headerLength + $iv.Length
        CipherLength = $authenticatedLength - $headerLength - $iv.Length
    }
}

function Test-BackupArchive {
    param([Parameter(Mandatory)][string]$InputPath)
    $archive = [IO.File]::OpenRead($InputPath)
    try { $null = Read-VerifiedBackupArchive $archive; return $true }
    finally { $archive.Dispose() }
}

function Write-DecryptedBackup {
    param([IO.Stream]$Archive, $Metadata, [IO.Stream]$DestinationStream)
    $aes = [Security.Cryptography.Aes]::Create()
    try {
        $aes.Mode = [Security.Cryptography.CipherMode]::CBC
        $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
        $aes.Key = $Metadata.EncryptionKey
        $aes.IV = $Metadata.Iv
        $Archive.Position = $Metadata.CipherOffset
        $crypto = [Security.Cryptography.CryptoStream]::new(
            $DestinationStream, $aes.CreateDecryptor(), [Security.Cryptography.CryptoStreamMode]::Write, $true)
        try {
            $remaining = [long]$Metadata.CipherLength
            [byte[]]$buffer = [byte[]]::new(65536)
            while ($remaining -gt 0) {
                $read = $Archive.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
                if ($read -le 0) { throw 'El contenido cifrado está truncado.' }
                $crypto.Write($buffer, 0, $read)
                $remaining -= $read
            }
            $crypto.FlushFinalBlock()
        } finally { $crypto.Dispose() }
    } finally { $aes.Dispose() }
}

function Unprotect-BackupArchive {
    param([Parameter(Mandatory)][string]$InputPath, [Parameter(Mandatory)][string]$OutputPath)
    $archive = [IO.File]::OpenRead($InputPath)
    try {
        $metadata = Read-VerifiedBackupArchive $archive
        $plain = [IO.File]::Open($OutputPath, [IO.FileMode]::CreateNew)
        try { Write-DecryptedBackup $archive $metadata $plain }
        finally { $plain.Dispose() }
    } finally { $archive.Dispose() }
}

function Unprotect-BackupArchiveToStream {
    param([Parameter(Mandatory)][string]$InputPath, [Parameter(Mandatory)][IO.Stream]$DestinationStream)
    $archive = [IO.File]::OpenRead($InputPath)
    try { Write-DecryptedBackup $archive (Read-VerifiedBackupArchive $archive) $DestinationStream }
    finally { $archive.Dispose() }
}

Export-ModuleMember -Function Protect-BackupArchive, Protect-BackupProcessOutput, Unprotect-BackupArchive,
    Test-BackupArchive, Unprotect-BackupArchiveToStream, Initialize-BackupEncryptionKeyStore
