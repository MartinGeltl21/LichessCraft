param(
  [Parameter(Mandatory)][string]$JavaHome,
  [Parameter(Mandatory)][string]$MavenCommand,
  [Parameter(Mandatory)][string]$RuntimeCache,
  [Parameter(Mandatory)][string]$Workspace
)
$ErrorActionPreference='Stop'
$taskRepo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskWorkspace=[IO.Path]::GetFullPath($Workspace)
if(Test-Path -LiteralPath $taskWorkspace){throw 'Use a fresh test workspace.'}
if(!(Test-Path -LiteralPath (Join-Path $RuntimeCache 'eula.txt')) -or !(Select-String -LiteralPath (Join-Path $RuntimeCache 'eula.txt') -Pattern '^eula=true$' -Quiet)){throw 'RuntimeCache must contain an already accepted Minecraft EULA.'}
New-Item -ItemType Directory -Path "$taskWorkspace/plugins/LichessCraft" -Force | Out-Null
foreach($taskDirectory in @('libraries','versions','cache')){Copy-Item -LiteralPath (Join-Path $RuntimeCache $taskDirectory) -Destination $taskWorkspace -Recurse}
Copy-Item -LiteralPath (Join-Path $RuntimeCache 'paper.jar') -Destination "$taskWorkspace/paper.jar"
Copy-Item -LiteralPath (Join-Path $RuntimeCache 'eula.txt') -Destination "$taskWorkspace/eula.txt"
$taskUtf8=[Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText("$taskWorkspace/server.properties",@'
server-ip=127.0.0.1
server-port=25589
online-mode=false
enable-rcon=false
view-distance=2
simulation-distance=2
spawn-protection=0
level-type=minecraft:flat
sync-chunk-writes=false
motd=Local LichessCraft test
'@,$taskUtf8)
[IO.File]::WriteAllText("$taskWorkspace/plugins/LichessCraft/config.yml",@'
oauth:
  public-url: 'https://example.invalid'
  bind: '127.0.0.1'
  port: 18765
  client-id: 'lichesscraft-test'
board:
  square-size: 4
'@,$taskUtf8)
$env:JAVA_HOME=$JavaHome
$env:Path="$JavaHome/bin;$env:Path"
Push-Location $taskRepo
try{
  & $MavenCommand --batch-mode --no-transfer-progress clean verify *> "$taskWorkspace/build.log"
  if($LASTEXITCODE -ne 0){Get-Content "$taskWorkspace/build.log" -Tail 35;throw 'Build failed.'}
  & $MavenCommand --batch-mode --no-transfer-progress dependency:build-classpath "-Dmdep.outputFile=$taskWorkspace/classpath.txt" *> "$taskWorkspace/classpath.log"
  if($LASTEXITCODE -ne 0){throw 'Classpath failed.'}
  $taskClassPath="$taskRepo/target/classes;$([IO.File]::ReadAllText("$taskWorkspace/classpath.txt").Trim())"
  New-Item -ItemType Directory -Path "$taskWorkspace/smoke-classes" | Out-Null
  & "$JavaHome/bin/javac.exe" -encoding UTF-8 -cp $taskClassPath -d "$taskWorkspace/smoke-classes" "$taskRepo/src/test/paper/PaperSmoke.java"
  if($LASTEXITCODE -ne 0){throw 'Smoke compile failed.'}
  Copy-Item -LiteralPath "$taskRepo/src/test/paper/plugin.yml" -Destination "$taskWorkspace/smoke-classes/plugin.yml"
  & "$JavaHome/bin/jar.exe" --create --file "$taskWorkspace/plugins/LichessCraftSmoke.jar" -C "$taskWorkspace/smoke-classes" .
  Copy-Item -LiteralPath "$taskRepo/target/LichessCraft-0.1.0.jar" -Destination "$taskWorkspace/plugins/LichessCraft.jar"
}finally{Pop-Location}
Push-Location $taskWorkspace
try{
  foreach($taskPhase in 1..2){
    & "$JavaHome/bin/java.exe" '-Dterminal.jline=false' '-Dterminal.ansi=false' -Xms512M -Xmx1G -jar paper.jar --nogui *> "$taskWorkspace/phase-$taskPhase.log"
    Get-Content -LiteralPath "$taskWorkspace/phase-$taskPhase.log" -Tail 22
    if($LASTEXITCODE -ne 0 -or !(Select-String -LiteralPath "$taskWorkspace/phase-$taskPhase.log" -Pattern 'SMOKE COMPLETE' -Quiet) -or (Select-String -LiteralPath "$taskWorkspace/phase-$taskPhase.log" -Pattern 'SMOKE FAILED' -Quiet)){throw "Paper phase $taskPhase failed."}
  }
}finally{Pop-Location}
'Paper smoke and restart checks passed.'
