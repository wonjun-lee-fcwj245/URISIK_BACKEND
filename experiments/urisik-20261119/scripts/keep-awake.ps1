Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class KA9 { [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint f); }'
[void][KA9]::SetThreadExecutionState(0x80000001)
while (-not (Test-Path 'C:\Users\이원준\Desktop\URISIK_BACKEND\experiments\urisik-20261119\scripts\keep-awake.stop')) { Start-Sleep -Seconds 30 }
[void][KA9]::SetThreadExecutionState(0x80000000)
