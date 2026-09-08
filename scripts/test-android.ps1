param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^emulator-[0-9]+$')]
    [string]$Serial,

    [ValidatePattern('^[A-Za-z0-9_.,#]+$')]
    [string]$TestClass = 'com.blackblast.app.GameScreenTest,com.blackblast.app.GameStoreTest,com.blackblast.app.GameViewModelTest,com.blackblast.app.MainActivityTest,com.blackblast.app.IoErrorIsolationTest,com.blackblast.app.LifecycleEffectTest,com.blackblast.app.StorageWriteReliabilityTest,com.blackblast.app.RecoveryScreenTest,com.blackblast.app.RetryScreenTest,com.blackblast.app.RuntimeJourneyTest,com.blackblast.app.AlgorithmTimingTest,com.blackblast.app.CampaignProgressTest,com.blackblast.app.LevelJourneyTest,com.blackblast.app.IntegratedStageProgressTest',

    [int]$ExpectedTests = 79
)

$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "Android platform tools not found at $adb" }

function Invoke-CapturedAdb([string]$Arguments) {
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $adb
    $process.StartInfo.Arguments = "-s $Serial $Arguments"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        if (-not $process.Start()) { throw 'Could not start adb.' }
        $handle = $process.Handle
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $output = $stdout.GetAwaiter().GetResult()
        $errors = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "adb exited $($process.ExitCode): $errors`n$output" }
        if ($errors) { Write-Warning $errors }
        return $output
    } finally {
        $process.Dispose()
    }
}

$identity = Invoke-CapturedAdb 'emu avd name'
if ($identity -notmatch '(?m)^OK\s*$') { throw "Could not verify emulator identity: $identity" }
Write-Output "Emulator: $($identity.Trim())"
$remoteReport = "/data/local/tmp/blackblast-$([guid]::NewGuid().ToString('N')).txt"
$instrumentArguments = 'shell "am instrument -w -r -e class ' + $TestClass +
    ' com.blackblast.app.test/androidx.test.runner.AndroidJUnitRunner > ' + $remoteReport + ' 2>&1"'
try {
    Invoke-CapturedAdb $instrumentArguments | Out-Null
} catch {
    Write-Warning "Instrumentation transport interrupted: $($_.Exception.Message). Checking device-local report $remoteReport"
}
$report = Invoke-CapturedAdb "shell cat $remoteReport"
Write-Output $report

$directory = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts\test-results'
[System.IO.Directory]::CreateDirectory($directory) | Out-Null
$reportPath = Join-Path $directory "android-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt"
[System.IO.File]::WriteAllText($reportPath, $report)

$statuses = [regex]::Matches($report, '(?m)^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$')
$passed = @($statuses | Where-Object { [int]$_.Groups[1].Value -eq 0 }).Count
$failed = @($statuses | Where-Object { [int]$_.Groups[1].Value -lt 0 }).Count
$summary = [regex]::IsMatch($report, "(?m)^OK \($ExpectedTests tests?\)\s*$")
if ($passed -ne $ExpectedTests -or $failed -ne 0 -or -not $summary) {
    throw "Android tests did not pass: $passed/$ExpectedTests passed, $failed failed or skipped. Report: $reportPath"
}
Write-Output "VERIFIED: $passed tests passed with zero failures or skips. Report: $reportPath"