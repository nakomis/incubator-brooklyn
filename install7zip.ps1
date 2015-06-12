$Path = "C:\InstallTemp"
New-Item -ItemType Directory -Force -Path $Path

$Url = "http://www.7-zip.org/a/7z938-x64.msi"

$Dl = [System.IO.Path]::Combine($Path, "installer.msi")

$WebClient = New-Object System.Net.WebClient

$WebClient.DownloadFile( $Url, $Dl )

Start-Process "msiexec" -ArgumentList '/qn','/i',$Dl -RedirectStandardOutput ( [System.IO.Path]::Combine($Path, "stdout.txt") ) -RedirectStandardError ( [System.IO.Path]::Combine($Path, "stderr.txt") ) -Wait
