param(
    [int]$Rows = 300000,
    [string]$OutputPath = "perf_data_extreme_unique_300000.csv"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$output = Join-Path $root $OutputPath

function To-Base26Code {
    param(
        [int]$Value,
        [int]$Width
    )
    $chars = New-Object char[] $Width
    $current = $Value
    for ($pos = $Width - 1; $pos -ge 0; $pos--) {
        $chars[$pos] = [char]([int](65 + ($current % 26)))
        $current = [int][Math]::Floor($current / 26)
    }
    return -join $chars
}

function To-Base36Code {
    param(
        [int]$Value,
        [int]$Width
    )
    $alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    $chars = New-Object char[] $Width
    $current = $Value
    for ($pos = $Width - 1; $pos -ge 0; $pos--) {
        $chars[$pos] = $alphabet[$current % 36]
        $current = [int][Math]::Floor($current / 36)
    }
    return -join $chars
}

$directory = Split-Path -Parent $output
if ($directory -and -not (Test-Path $directory)) {
    New-Item -ItemType Directory -Path $directory | Out-Null
}

$writer = [System.IO.StreamWriter]::new($output, $false, [System.Text.UTF8Encoding]::new($false), 1MB)
try {
    $writer.WriteLine("user_id,serial_no,user_code,business_key,id_card,phone,name,email,device_id,trans_id,secret_code")
    for ($i = 0; $i -lt $Rows; $i++) {
        $n = $i + 1

        # user_id is constrained to 5 digits, so 300k rows can only provide 100k unique values.
        $userId = "{0:D5}" -f ($i % 100000)
        $serialNo = "{0:D13}" -f (1000000000000 + $n)
        $userCode = To-Base26Code $i 4
        $businessKey = "BK{0:D8}x{1:X4}" -f $n, ([int](($n * 7919) % 65536))
        $idCard = "{0:D17}X" -f (10000000000000000 + $n)
        $phone = "13{0:D9}" -f $n
        $name = "Name{0:D6}" -f $n
        $email = "u{0:D6}@bench.local" -f $n
        $deviceId = "{0:X2}:{1:X2}:{2:X2}:{3:X2}:{4:X2}:{5:X2}" -f (($n -shr 40) -band 255), (($n -shr 32) -band 255), (($n -shr 24) -band 255), (($n -shr 16) -band 255), (($n -shr 8) -band 255), ($n -band 255)
        $transId = "{0:D18}" -f (100000000000000000 + $n)
        $secretCode = To-Base36Code $i 6

        $writer.Write($userId)
        $writer.Write(',')
        $writer.Write($serialNo)
        $writer.Write(',')
        $writer.Write($userCode)
        $writer.Write(',')
        $writer.Write($businessKey)
        $writer.Write(',')
        $writer.Write($idCard)
        $writer.Write(',')
        $writer.Write($phone)
        $writer.Write(',')
        $writer.Write($name)
        $writer.Write(',')
        $writer.Write($email)
        $writer.Write(',')
        $writer.Write($deviceId)
        $writer.Write(',')
        $writer.Write($transId)
        $writer.Write(',')
        $writer.WriteLine($secretCode)
    }
}
finally {
    $writer.Close()
}

$file = Get-Item $output
[PSCustomObject]@{
    OutputPath = $file.FullName
    Rows = $Rows
    SizeMB = [Math]::Round($file.Length / 1MB, 2)
    UserIdUnique = [Math]::Min($Rows, 100000)
    OtherFieldUnique = $Rows
} | Format-List
