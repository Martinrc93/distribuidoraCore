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

function Protect-BackupArchive {
    param([Parameter(Mandatory)][string]$InputPath, [Parameter(Mandatory)][string]$OutputPath)
    $material = Get-BackupKeyMaterial
    [byte[]]$encryptionKey = $material[0..31]
    [byte[]]$macKey = $material[32..63]
    [byte[]]$header = [Text.Encoding]::ASCII.GetBytes('DCOREBK1')
    [byte[]]$iv = [Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
    $aes = [Security.Cryptography.Aes]::Create()
    $aes.KeySize = 256
    $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
    $aes.Key = $encryptionKey
    $aes.IV = $iv
    $source = [IO.File]::OpenRead($InputPath)
    $destination = [IO.File]::Create($OutputPath)
    try {
        $destination.Write($header, 0, $header.Length)
        $destination.Write($iv, 0, $iv.Length)
        $crypto = [Security.Cryptography.CryptoStream]::new(
            $destination, $aes.CreateEncryptor(), [Security.Cryptography.CryptoStreamMode]::Write, $true)
        try { $source.CopyTo($crypto); $crypto.FlushFinalBlock() }
        finally { $crypto.Dispose() }
    } finally { $source.Dispose(); $destination.Dispose(); $aes.Dispose() }

    $hmac = [Security.Cryptography.HMACSHA256]::new($macKey)
    $archive = [IO.File]::OpenRead($OutputPath)
    try { [byte[]]$tag = $hmac.ComputeHash($archive) }
    finally { $archive.Dispose(); $hmac.Dispose() }
    $append = [IO.File]::Open($OutputPath, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $append.Write($tag, 0, $tag.Length) }
    finally { $append.Dispose() }
}

function Protect-BackupProcessOutput {
    param([Parameter(Mandatory)][Diagnostics.ProcessStartInfo]$StartInfo,
          [Parameter(Mandatory)][string]$OutputPath)
    $material = Get-BackupKeyMaterial
    [byte[]]$encryptionKey = $material[0..31]
    [byte[]]$macKey = $material[32..63]
    [byte[]]$header = [Text.Encoding]::ASCII.GetBytes('DCOREBK1')
    [byte[]]$iv = [Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
    $aes = [Security.Cryptography.Aes]::Create()
    $aes.KeySize = 256
    $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
    $aes.Key = $encryptionKey
    $aes.IV = $iv
    $process = [Diagnostics.Process]::Start($StartInfo)
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $destination = [IO.File]::Create($OutputPath)
    try {
        $destination.Write($header, 0, $header.Length)
        $destination.Write($iv, 0, $iv.Length)
        $crypto = [Security.Cryptography.CryptoStream]::new(
            $destination, $aes.CreateEncryptor(), [Security.Cryptography.CryptoStreamMode]::Write, $true)
        try {
            $process.StandardOutput.BaseStream.CopyTo($crypto)
            $crypto.FlushFinalBlock()
        } finally { $crypto.Dispose() }
    } finally { $destination.Dispose(); $aes.Dispose() }
    $process.WaitForExit()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        Remove-Item -LiteralPath $OutputPath -Force -ErrorAction SilentlyContinue
        throw "pg_dump falló (código $($process.ExitCode)): $stderr"
    }
    if (-not (Test-Path -LiteralPath $OutputPath) -or (Get-Item -LiteralPath $OutputPath).Length -lt 80) {
        Remove-Item -LiteralPath $OutputPath -Force -ErrorAction SilentlyContinue
        throw 'pg_dump no produjo un archivo de backup válido.'
    }
    $hmac = [Security.Cryptography.HMACSHA256]::new($macKey)
    $archive = [IO.File]::OpenRead($OutputPath)
    try { [byte[]]$tag = $hmac.ComputeHash($archive) }
    finally { $archive.Dispose(); $hmac.Dispose() }
    $append = [IO.File]::Open($OutputPath, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $append.Write($tag, 0, $tag.Length) }
    finally { $append.Dispose() }
}

function Unprotect-BackupArchive {
    param([Parameter(Mandatory)][string]$InputPath, [Parameter(Mandatory)][string]$OutputPath)
    $material = Get-BackupKeyMaterial
    [byte[]]$encryptionKey = $material[0..31]
    [byte[]]$macKey = $material[32..63]
    [byte[]]$expectedHeader = [Text.Encoding]::ASCII.GetBytes('DCOREBK1')
    $headerLength = $expectedHeader.Length
    $tagLength = 32
    $archive = [IO.File]::OpenRead($InputPath)
    $cipherPath = "$OutputPath.cipher.tmp"
    try {
        if ($archive.Length -le ($headerLength + 16 + $tagLength)) { throw "El archivo de backup está truncado." }
        [byte[]]$header = [byte[]]::new($headerLength)
        [byte[]]$iv = [byte[]]::new(16)
        if ($archive.Read($header, 0, $header.Length) -ne $header.Length -or
            -not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals($header, $expectedHeader)) {
            throw "El formato del backup no es válido."
        }
        if ($archive.Read($iv, 0, $iv.Length) -ne $iv.Length) { throw "El backup no contiene un vector de inicialización válido." }

        $authenticatedLength = $archive.Length - $tagLength
        $archive.Position = 0
        $hmac = [Security.Cryptography.HMACSHA256]::new($macKey)
        [byte[]]$buffer = [byte[]]::new(65536)
        $remaining = $authenticatedLength
        try {
            while ($remaining -gt 0) {
                $read = $archive.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
                if ($read -le 0) { throw "El backup está truncado." }
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
            throw "La autenticación del backup falló; no se intentará restaurar."
        }

        $cipherLength = $authenticatedLength - $headerLength - $iv.Length
        $archive.Position = $headerLength + $iv.Length
        $cipherFile = [IO.File]::Create($cipherPath)
        try {
            $remaining = $cipherLength
            while ($remaining -gt 0) {
                $read = $archive.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
                if ($read -le 0) { throw "El contenido cifrado está truncado." }
                $cipherFile.Write($buffer, 0, $read)
                $remaining -= $read
            }
        } finally { $cipherFile.Dispose() }
    } finally { $archive.Dispose() }

    $aes = [Security.Cryptography.Aes]::Create()
    $aes.KeySize = 256
    $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
    $aes.Key = $encryptionKey
    $aes.IV = $iv
    $encrypted = [IO.File]::OpenRead($cipherPath)
    $plain = [IO.File]::Create($OutputPath)
    try {
        $crypto = [Security.Cryptography.CryptoStream]::new(
            $encrypted, $aes.CreateDecryptor(), [Security.Cryptography.CryptoStreamMode]::Read)
        try { $crypto.CopyTo($plain) }
        finally { $crypto.Dispose() }
    } finally {
        $encrypted.Dispose(); $plain.Dispose(); $aes.Dispose()
        Remove-Item -LiteralPath $cipherPath -Force -ErrorAction SilentlyContinue
    }
}

function Test-BackupArchive {
    param([Parameter(Mandatory)][string]$InputPath)
    $material = Get-BackupKeyMaterial
    [byte[]]$encryptionKey = $material[0..31]
    [byte[]]$macKey = $material[32..63]
    [byte[]]$expectedHeader = [Text.Encoding]::ASCII.GetBytes('DCOREBK1')
    $archive = [IO.File]::OpenRead($InputPath)
    try {
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
    } finally { $archive.Dispose() }
}

function Unprotect-BackupArchiveToStream {
    param([Parameter(Mandatory)][string]$InputPath, [Parameter(Mandatory)][IO.Stream]$DestinationStream)
    $metadata = Test-BackupArchive -InputPath $InputPath
    $cipherPath = Join-Path ([IO.Path]::GetTempPath()) ("dcore-cipher-" + [guid]::NewGuid() + '.tmp')
    $archive = [IO.File]::OpenRead($InputPath)
    $cipher = [IO.File]::Create($cipherPath)
    try {
        $archive.Position = $metadata.CipherOffset
        $remaining = [long]$metadata.CipherLength
        [byte[]]$buffer = [byte[]]::new(65536)
        while ($remaining -gt 0) {
            $read = $archive.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
            if ($read -le 0) { throw 'El contenido cifrado está truncado.' }
            $cipher.Write($buffer, 0, $read)
            $remaining -= $read
        }
    } finally { $archive.Dispose(); $cipher.Dispose() }

    $aes = [Security.Cryptography.Aes]::Create()
    $aes.KeySize = 256
    $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
    $aes.Key = $metadata.EncryptionKey
    $aes.IV = $metadata.Iv
    $encrypted = [IO.File]::OpenRead($cipherPath)
    try {
        $crypto = [Security.Cryptography.CryptoStream]::new(
            $encrypted, $aes.CreateDecryptor(), [Security.Cryptography.CryptoStreamMode]::Read)
        try { $crypto.CopyTo($DestinationStream) }
        finally { $crypto.Dispose() }
    } finally {
        $encrypted.Dispose(); $aes.Dispose()
        Remove-Item -LiteralPath $cipherPath -Force -ErrorAction SilentlyContinue
    }
}

Export-ModuleMember -Function Protect-BackupArchive, Protect-BackupProcessOutput, Unprotect-BackupArchive,
    Test-BackupArchive, Unprotect-BackupArchiveToStream, Initialize-BackupEncryptionKeyStore
