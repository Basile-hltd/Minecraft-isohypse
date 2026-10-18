<#
.SYNOPSIS
  Build le plugin, télécharge Paper et lance un serveur local de test.

.DESCRIPTION
  Tout est créé dans tmp/ à la racine du repo (ignoré par git) :
    tmp/paper-<mc>-<build>.jar   jar Paper téléchargé
    tmp/gradle-<ver>/            Gradle, téléchargé si "gradle" est absent du PATH
    tmp/server/                  dossier d'exécution du serveur (monde, plugins, logs)

.PARAMETER McVersion
  Version de Minecraft / Paper ciblée (défaut : 26.2).

.PARAMETER Clean
  Supprime tmp/server avant de lancer (repart d'un monde vierge).

.EXAMPLE
  .\tools\local_run.ps1 -McVersion 26.2
#>
param(
    [string]$McVersion = "26.2",
    [string]$Memory = "4G",
    [string]$Player = "Japrisot",
    [int]$MinY = 0,
    [int]$WorldHeight = 2000,
    [string]$HeightMap = "",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$GradleVersion = "9.2.0"
$Root = Split-Path -Parent $PSScriptRoot
$Tmp = Join-Path $Root "tmp"
$ServerDir = Join-Path $Tmp "server"
New-Item -ItemType Directory -Force $Tmp | Out-Null

# --- Gradle -------------------------------------------------------------
$gradle = (Get-Command gradle -ErrorAction SilentlyContinue).Source
if (-not $gradle) {
    $gradleHome = Join-Path $Tmp "gradle-$GradleVersion"
    $gradle = Join-Path $gradleHome "bin\gradle.bat"
    if (-not (Test-Path $gradle)) {
        Write-Host "Téléchargement de Gradle $GradleVersion..."
        $zip = Join-Path $Tmp "gradle-$GradleVersion-bin.zip"
        Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $zip
        Expand-Archive $zip -DestinationPath $Tmp -Force
        Remove-Item $zip
    }
}

# --- Build du plugin ----------------------------------------------------
# Même résolution de l'API Paper que la CI : dernière version stable pour $McVersion.
$buildArgs = @("build", "-PmcVersion=$McVersion")
try {
    $meta = (Invoke-WebRequest "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml").Content
    $pattern = "<version>(" + [regex]::Escape($McVersion) + "\.[^<]*-stable)</version>"
    $api = [regex]::Matches($meta, $pattern) | ForEach-Object { $_.Groups[1].Value } |
        Sort-Object { [version]($_ -replace '^(\d+(\.\d+)*).*', '$1') } | Select-Object -Last 1
    if ($api) { $buildArgs += "-PpaperApi=$api" }
} catch {
    Write-Warning "API Paper non résolue, utilisation de la valeur par défaut du build."
}

Write-Host "Build du plugin (Minecraft $McVersion)..."
Push-Location $Root
try {
    & $gradle @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "Le build Gradle a échoué." }
} finally {
    Pop-Location
}

$pluginJar = Get-ChildItem (Join-Path $Root "build\libs") -Filter "isohypse-*-minecraft-$McVersion.jar" |
    Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $pluginJar) { throw "Jar du plugin introuvable dans build/libs." }

# --- Téléchargement de Paper -------------------------------------------
# "26.1" est une famille : on prend la dernière version stable (hors rc/pre), ex. 26.1.2.
$project = Invoke-RestMethod "https://fill.papermc.io/v3/projects/paper"
$paperVersion = @($project.versions.$McVersion) | Where-Object { $_ -notmatch "-(rc|pre)" } | Select-Object -First 1
if (-not $paperVersion) { throw "Aucune version Paper stable pour $McVersion." }

$build = Invoke-RestMethod "https://fill.papermc.io/v3/projects/paper/versions/$paperVersion/builds/latest"
$download = $build.downloads."server:default"
$paperJar = Join-Path $Tmp "paper-$paperVersion-$($build.id).jar"
if (-not (Test-Path $paperJar)) {
    Write-Host "Téléchargement de Paper $paperVersion build $($build.id)..."
    Invoke-WebRequest $download.url -OutFile $paperJar
    $hash = (Get-FileHash $paperJar -Algorithm SHA256).Hash
    if ($hash -ne $download.checksums.sha256.ToUpper()) {
        Remove-Item $paperJar
        throw "Checksum SHA-256 de Paper invalide."
    }
}

# --- Préparation et lancement du serveur -------------------------------
if ($Clean -and (Test-Path $ServerDir)) { Remove-Item $ServerDir -Recurse -Force }
$plugins = Join-Path $ServerDir "plugins"
New-Item -ItemType Directory -Force $plugins | Out-Null

# Une seule version du plugin à la fois dans le serveur.
Get-ChildItem $plugins -Filter "isohypse-*.jar" | Remove-Item -Force
Copy-Item $pluginJar.FullName $plugins

# Plugins tiers (ex. Chunksmith) : tout jar placé dans tools/plugins est copié dans le serveur.
$extra = Join-Path $PSScriptRoot "plugins"
if (Test-Path $extra) { Copy-Item (Join-Path $extra "*.jar") $plugins -Force }

# Height map (PNG 16 bits ou BMP 8 bits) : copiée dans le dossier de données du plugin.
if (-not $HeightMap) {
    $repo = Split-Path -Parent $PSScriptRoot
    $HeightMap = @("heightmap.png", "heightmap.bmp") | ForEach-Object { Join-Path $repo $_ } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}
if ($HeightMap -and (Test-Path $HeightMap)) {
    $dataDir = Join-Path $plugins "Isohypse"
    New-Item -ItemType Directory -Force $dataDir | Out-Null
    Get-ChildItem $dataDir -Filter "heightmap.*" | Remove-Item -Force
    Copy-Item $HeightMap (Join-Path $dataDir ("heightmap" + [IO.Path]::GetExtension($HeightMap))) -Force
} else {
    Write-Warning "Height map introuvable : carte plate."
}

# Plugins tiers (ex. Chunksmith) : tout jar placé dans tools/plugins est copié dans le serveur.
$extra = Join-Path $PSScriptRoot "plugins"
if (Test-Path $extra) { Copy-Item (Join-Path $extra "*.jar") $plugins -Force }

# Height map (BMP 8 bits) : copiée dans le dossier de données du plugin si elle existe.
if (Test-Path $HeightMap) {
    $dataDir = Join-Path $plugins "Isohypse"
    New-Item -ItemType Directory -Force $dataDir | Out-Null
    Copy-Item $HeightMap (Join-Path $dataDir "heightmap.bmp") -Force
} else {
    Write-Warning "Height map introuvable ($HeightMap) : carte plate."
}

# Serveur local jetable : l'EULA Mojang est acceptée automatiquement.
Set-Content (Join-Path $ServerDir "eula.txt") "eula=true"

# Hauteur du monde (uniquement si -MinY ou -WorldHeight est passé : le monde vanilla n'a plus besoin d'être
# haut maintenant que le plugin ne génère plus de terrain) : datapack qui redéfinit le type de dimension de
# l'overworld (gabarit 26.3 dans tools/datapack). min_y et hauteur doivent être des multiples de 16, et
# min_y + hauteur <= 2032.
if ($PSBoundParameters.ContainsKey('MinY') -or $PSBoundParameters.ContainsKey('WorldHeight')) {
    if ($MinY % 16 -ne 0 -or $WorldHeight % 16 -ne 0 -or $WorldHeight -lt 16 -or $MinY + $WorldHeight -gt 2032) {
        throw "MinY et WorldHeight doivent être des multiples de 16, avec MinY + WorldHeight <= 2032."
    }
    $packDir = Join-Path $ServerDir "world\datapacks\isohypse"
    if ((Test-Path (Join-Path $ServerDir "world\level.dat")) -and -not (Test-Path $packDir)) {
        throw "Le monde existant n'a pas la hauteur personnalisée : relancer avec -Clean."
    }
    New-Item -ItemType Directory -Force (Join-Path $packDir "data\minecraft\dimension_type") | Out-Null
    Set-Content (Join-Path $packDir "pack.mcmeta") '{"pack":{"description":"Isohypse - hauteur du monde","min_format":121,"max_format":121}}'
    $dim = Get-Content (Join-Path $PSScriptRoot "datapack\overworld.json") -Raw | ConvertFrom-Json
    $dim.min_y = $MinY
    $dim.height = $WorldHeight
    $dim.logical_height = $WorldHeight
    $dim | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $packDir "data\minecraft\dimension_type\overworld.json")
}

# Whitelist désactivée, spectateur par défaut (server.properties est complété par Paper au 1er lancement).
$propsFile = Join-Path $ServerDir "server.properties"
$props = [ordered]@{ "white-list" = "false"; "enforce-whitelist" = "false"; "gamemode" = "spectator" }
$lines = [System.Collections.Generic.List[string]]::new()
if (Test-Path $propsFile) { $lines.AddRange([string[]]@(Get-Content $propsFile)) }
foreach ($key in $props.Keys) {
    $line = "$key=$($props[$key])"
    $idx = $lines.FindIndex([Predicate[string]] { param($l) $l -like "$key=*" })
    if ($idx -ge 0) { $lines[$idx] = $line } else { $lines.Add($line) }
}
Set-Content $propsFile $lines

# Le plugin n'a plus de générateur de monde : on retire l'ancienne ligne "generator: Isohypse" du bukkit.yml.
$bukkitFile = Join-Path $ServerDir "bukkit.yml"
if ((Test-Path $bukkitFile) -and (Select-String -Path $bukkitFile -Pattern "generator: Isohypse" -Quiet)) {
    Remove-Item $bukkitFile
}

# Vol rapide / téléportations : plus de "moved too quickly" ni de retour en arrière (serveur local jetable).
# spigot.yml n'est écrit que s'il n'existe pas ; Spigot complète le reste au premier lancement.
$spigotFile = Join-Path $ServerDir "spigot.yml"
if (-not (Test-Path $spigotFile)) {
    Set-Content $spigotFile "settings:`n  moved-too-quickly-multiplier: 1000.0`n  moved-wrongly-threshold: 1000.0"
}

# Joueur op (niveau 4) : l'UUID vient de l'API Mojang.
try {
    $profile = Invoke-RestMethod "https://api.mojang.com/users/profiles/minecraft/$Player"
    $uuid = $profile.id -replace '^(.{8})(.{4})(.{4})(.{4})(.{12})$', '$1-$2-$3-$4-$5'
    $ops = @(@{ uuid = $uuid; name = $profile.name; level = 4; bypassesPlayerLimit = $true })
    ConvertTo-Json -InputObject $ops | Set-Content (Join-Path $ServerDir "ops.json")
} catch {
    Write-Warning "UUID de '$Player' introuvable, il ne sera pas op (utiliser 'op $Player' dans la console)."
}

Write-Host "Lancement du serveur (taper 'stop' pour l'arrêter)..."
Push-Location $ServerDir
try {
    & java "-Xms$Memory" "-Xmx$Memory" -jar $paperJar --nogui
} finally {
    Pop-Location
}
