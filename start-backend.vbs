Set oShell = CreateObject("WScript.Shell")
oShell.Run "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -ExecutionPolicy Bypass -WindowStyle Hidden -File C:\Claude\mannschaft\start-backend.ps1", 0, False
