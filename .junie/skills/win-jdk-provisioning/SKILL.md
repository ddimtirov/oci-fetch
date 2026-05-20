---
name: win-jdk-provisioning
description: Automated provisioning and installation of the 64-bit Eclipse Temurin 25 JDK on Windows environments. Use this skill when Java is missing on a Windows host to bootstrap the development and build environment.
---

# Windows JDK Provisioning

This skill provides a reliable, automated procedure to detect, bootstrap, and install the required Java Development Kit (JDK) on a Windows system when Java is missing.

---

## 1. Shell Selection

Before running any commands, determine the available PowerShell shell environment on the Windows host:

1. Attempt to locate or execute `pwsh.exe` (PowerShell Core).
2. If `pwsh.exe` is missing or unavailable, fallback to using `PowerShell.exe` (Windows PowerShell).

---

## 2. Java Detection

To verify if Java is installed and configured in the system PATH:

```powershell
java -version
```

If this command fails or indicates that `java` is not recognized, proceed with the winget bootstrapping and JDK installation steps below.

---

## 3. Winget Bootstrapping

We use `winget` (Windows Package Manager) to install the JDK. If `winget` is missing or not functional on the host, bootstrap it using the selected PowerShell shell by running the following commands under a bypassed and unblocked context to avoid interactive trust prompts:

```powershell
# Save the current CurrentUser execution policy
$previousPolicy = (Get-ExecutionPolicy -Scope CurrentUser)

# Set execution policy to Bypass for the current user to prevent execution policy/signature blocks
Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope CurrentUser -Force

try {
    # Install NuGet package provider
    Install-PackageProvider -Name NuGet -Force | Out-Null

    # Install the WinGet Client module
    Install-Module -Name Microsoft.WinGet.Client -Force -AllowClobber | Out-Null

    # Unblock files in the imported module folder to prevent untrusted publisher interactive prompts
    Get-ChildItem -Path "$Home\Documents\WindowsPowerShell\Modules\Microsoft.WinGet.Client" -Recurse -ErrorAction SilentlyContinue | Unblock-File

    # Register and repair the WinGet Package Manager.
    # First attempt a user-scope repair, then fallback to AllUsers if needed:
    try {
        Repair-WinGetPackageManager -ErrorAction Stop
    } catch {
        Repair-WinGetPackageManager -AllUsers -ErrorAction SilentlyContinue
    }
} finally {
    # Restore the previous CurrentUser execution policy
    Set-ExecutionPolicy -ExecutionPolicy $previousPolicy -Scope CurrentUser -Force
}
```

---

## 4. Install Temurin 25 JDK

Once `winget` is available, install the **64-bit Eclipse Temurin 25 JDK** by running:

```powershell
winget install --id EclipseAdoptium.Temurin.25.JDK --architecture x64 --exact --silent --accept-package-agreements --accept-source-agreements
```

> [!NOTE]
> If the specific major version ID (`EclipseAdoptium.Temurin.25.JDK`) is not found, you can search for the available Temurin 25 packages using:
> `winget search Temurin`

### Manual Fallback: Direct MSI Download & Silent Installation
> [!WARNING]
> DO NOT automatically fallback to direct MSI installation without user approval, as direct MSI installations do not receive automatic updates via package managers. Always stop and ask/wait for explicit user instruction before utilizing this manual fallback.

If authorized by the user, you can download and install the JDK directly using a headless .NET WebClient and silent `msiexec` call:

```powershell
# Fetch the latest GA Temurin 25 MSI download URL from the Adoptium API
$resp = Invoke-RestMethod -Uri "https://api.adoptium.net/v3/assets/feature_releases/25/ga?architecture=x64&image_type=jdk&os=windows" -Method Get
$msiUrl = $resp[0].binaries[0].installer.link
$tempMsi = Join-Path $env:TEMP "temurin-25.msi"

# Download the MSI packages
$webClient = New-Object System.Net.WebClient
$webClient.DownloadFile($msiUrl, $tempMsi)

# Silently install the MSI with all required path/environment features enabled
$process = Start-Process msiexec.exe -ArgumentList "/i `"$tempMsi`" /qn /norestart ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJarFileRunWith,FeatureJavaHome" -Wait -NoNewWindow -PassThru

if ($process.ExitCode -eq 0) {
    Write-Host "Temurin 25 JDK installed successfully!"
} else {
    Write-Error "Installation failed with exit code $($process.ExitCode)"
}
```

---

## 5. Verification

After installation is complete, refresh the environment variables in the current session (or start a new shell session) and verify the installation:

```powershell
# Verify Java version
java -version

# Verify JAVA_HOME environment variable
$env:JAVA_HOME
```
