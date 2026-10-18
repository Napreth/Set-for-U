[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$BuildArguments)
$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'build.py') @BuildArguments
if ($LASTEXITCODE -ne 0) { throw 'Set-for-U build failed.' }
