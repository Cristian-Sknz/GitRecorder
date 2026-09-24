param(
    [string]$AppJar = (Join-Path $PSScriptRoot '..\build\gitrecorder.jar')
)

$ErrorActionPreference = 'Stop'
$buildRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\build'))
$root = [IO.Path]::GetFullPath((Join-Path $buildRoot 'integration'))
if (-not $root.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Diretório de teste fora de build.'
}
if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
New-Item -ItemType Directory -Path $root | Out-Null
$sourceRemote = Join-Path $root 'source.git'
$destinationRemote = Join-Path $root 'destination.git'
$source = Join-Path $root 'source'
$store = Join-Path $root 'store'
$store2 = Join-Path $root 'store2'
$AppJar = (Resolve-Path -LiteralPath $AppJar).Path

function RunGit([string[]]$GitArgs) {
    & git @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "Git falhou: $($GitArgs -join ' ')" }
}
function RunApp([string[]]$AppArgs) {
    $output = & java -jar $AppJar @AppArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "GitRecorder falhou: $($AppArgs -join ' ')`n$output" }
    return ($output | Out-String)
}
function Assert([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Commit([string]$Message, [string]$Email, [string]$Date) {
    $env:GIT_AUTHOR_NAME = 'Test Author'
    $env:GIT_AUTHOR_EMAIL = $Email
    $env:GIT_AUTHOR_DATE = $Date
    $env:GIT_COMMITTER_NAME = 'Test Author'
    $env:GIT_COMMITTER_EMAIL = $Email
    $env:GIT_COMMITTER_DATE = $Date
    RunGit @('commit', '-m', $Message)
}

RunGit @('init', '--bare', '-b', 'main', $sourceRemote)
RunGit @('init', '--bare', '-b', 'main', $destinationRemote)
RunGit @('init', '-b', 'main', $source)
Push-Location $source
try {
    RunGit @('config', 'commit.gpgsign', 'false')
    RunGit @('remote', 'add', 'origin', $sourceRemote)
    Set-Content -LiteralPath 'work.txt' -Value 'one'
    RunGit @('add', 'work.txt')
    Commit 'Initial "quoted" work' 'me@example.com' '2024-01-01T10:00:00+00:00'
    Set-Content -LiteralPath 'work.txt' -Value 'two'
    RunGit @('add', 'work.txt')
    Commit 'Other author' 'other@example.com' '2024-01-02T10:00:00+00:00'
    RunGit @('checkout', '-b', 'feature')
    Set-Content -LiteralPath 'feature.txt' -Value 'feature'
    RunGit @('add', 'feature.txt')
    Commit 'Feature work' 'me@example.com' '2024-01-03T10:00:00+00:00'
    RunGit @('checkout', 'main')
    $env:GIT_AUTHOR_NAME = 'Other'; $env:GIT_AUTHOR_EMAIL = 'other@example.com'
    $env:GIT_COMMITTER_NAME = 'Other'; $env:GIT_COMMITTER_EMAIL = 'other@example.com'
    $env:GIT_AUTHOR_DATE = '2024-01-04T10:00:00+00:00'; $env:GIT_COMMITTER_DATE = $env:GIT_AUTHOR_DATE
    RunGit @('merge', '--no-ff', 'feature', '-m', 'Merged PR 42 feature')
    RunGit @('push', '-u', 'origin', 'main')

    $output = RunApp @('init', '--remote', $destinationRemote, '--store', $store)
    RunApp @('email', 'add', 'me@example.com', '--store', $store) | Out-Null
    $output = RunApp @('sync', '--store', $store, '--web-url', 'https://dev.azure.com/Org/Project/_git/Repo', '--repo-id', 'fixture')
    Assert ($output -match 'Criados: 2') "Esperados 2 commits espelhados: $output"
    $refs = @(& git -C $store for-each-ref --format='%(refname:short)' refs/heads/sources)
    Assert ($refs.Count -eq 1) 'Branch do projeto ausente.'
    $mirrorBranch = $refs[0]
    $mirrorCommits = @(& git -C $store log $mirrorBranch --format='%H' --grep='Source-SHA:')
    Assert ($mirrorCommits.Count -eq 2) 'Quantidade de espelhos incorreta.'
    $featureMirror = & git -C $store log $mirrorBranch --format='%H' --grep='Feature work' -1
    $message = & git -C $store show -s --format='%B' $featureMirror
    Assert (($message | Out-String) -match 'Source-PR: https://dev.azure.com/Org/Project/_git/Repo/pullrequest/42') 'Link da PR ausente.'
    $tree = & git -C $store rev-parse "$featureMirror`^{tree}"
    $emptyTree = & git -C $store mktree
    Assert ($tree -eq $emptyTree) 'Commit espelhado contém arquivos.'
    $author = & git -C $store show -s --format='%ae %aI' $featureMirror
    Assert ($author -eq 'me@example.com 2024-01-03T10:00:00Z') 'Autoria ou data incorreta.'
    $output = RunApp @('sync', '--store', $store, '--web-url', 'https://dev.azure.com/Org/Project/_git/Repo', '--repo-id', 'fixture')
    Assert ($output -match 'Criados: 0') 'Segunda sincronização duplicou commits.'
    $output = RunApp @('sync', $sourceRemote, '--store', $store, '--web-url', 'https://dev.azure.com/Org/Project/_git/Repo', '--repo-id', 'fixture')
    Assert ($output -match 'Criados: 0') 'Sincronização por URL duplicou commits.'
    $output = RunApp @('sync', '--url', $sourceRemote, '--store', $store, '--web-url', 'https://dev.azure.com/Org/Project/_git/Repo', '--repo-id', 'fixture')
    Assert ($output -match 'Criados: 0') 'A opção --url falhou.'

    RunGit @('checkout', '-b', 'open-pr')
    Set-Content -LiteralPath 'open.txt' -Value 'pending'
    RunGit @('add', 'open.txt')
    Commit 'Open PR work' 'me@example.com' '2024-01-05T10:00:00+00:00'
    RunGit @('push', '-u', 'origin', 'open-pr')
    $mainBefore = & git -C $store rev-parse main
    $output = RunApp @('sync', '--branch', 'open-pr', '--store', $store, '--web-url', 'https://dev.azure.com/Org/Project/_git/Repo', '--repo-id', 'fixture')
    Assert ($output -match 'Criados: 3') 'Branch alternativa deveria conter sua ancestralidade publicada.'
    $mainAfter = & git -C $store rev-parse main
    Assert ($mainBefore -eq $mainAfter) 'Branch alternativa entrou antecipadamente em main.'
    RunGit @('checkout', 'main')
    $env:GIT_AUTHOR_NAME = 'Other'; $env:GIT_AUTHOR_EMAIL = 'other@example.com'
    $env:GIT_COMMITTER_NAME = 'Other'; $env:GIT_COMMITTER_EMAIL = 'other@example.com'
    $env:GIT_AUTHOR_DATE = '2024-01-06T10:00:00+00:00'; $env:GIT_COMMITTER_DATE = $env:GIT_AUTHOR_DATE
    RunGit @('merge', '--no-ff', 'open-pr', '-m', 'Merged PR 43: open work')
    RunGit @('push', 'origin', 'main')
    $output = RunApp @('sync', '--store', $store, '--web-url', 'https://dev.azure.com/Org/Project/_git/Repo', '--repo-id', 'fixture')
    Assert ($output -match 'Criados: 1') 'Commit da PR integrada deveria ser espelhado em main.'
    $status = RunApp @('status', '--store', $store)
    Assert ($status -match 'Push pendente: sim') 'Status não detectou push pendente.'
    RunApp @('push', '--store', $store) | Out-Null
    $status = RunApp @('status', '--store', $store)
    Assert ($status -match 'Push pendente: n') 'Status não reconheceu push concluído.'
    RunApp @('init', '--remote', $destinationRemote, '--store', $store2) | Out-Null
    $emails = RunApp @('email', 'list', '--store', $store2)
    Assert ($emails -match 'me@example.com') 'Metadados não foram recuperados em outro clone.'
    $status = RunApp @('status', '--store', $store2)
    Assert ($status -match 'Branches de origem: 2') 'Branches de origem não foram recuperadas em outro clone.'
    $output = RunApp @('sync', '--store', $store2, '--web-url', 'https://dev.azure.com/Org/Project/_git/Repo', '--repo-id', 'fixture')
    Assert ($output -match 'Criados: 0') 'Novo clone duplicou histórico.'

    RunApp @('email', 'add', 'other@example.com', '--store', $store2) | Out-Null
    $output = RunApp @('sync', '--store', $store2, '--web-url', 'https://dev.azure.com/Org/Project/_git/Repo', '--repo-id', 'fixture')
    Assert ($output -match 'Criados: 3') 'Novo e-mail não importou histórico antigo.'
    RunApp @('push', '--store', $store2) | Out-Null

    Set-Content -LiteralPath 'local-only.txt' -Value 'unpublished'
    RunGit @('add', 'local-only.txt')
    Commit 'Local only' 'me@example.com' '2024-01-07T10:00:00+00:00'
    $output = RunApp @('sync', '--store', $store2, '--web-url', 'https://dev.azure.com/Org/Project/_git/Repo', '--repo-id', 'fixture')
    Assert ($output -match 'Criados: 0') 'Commit local não publicado foi espelhado.'

    RunApp @('email', 'add', 'another@example.com', '--store', $store) | Out-Null
    $output = & java -jar $AppJar push --store $store 2>&1
    Assert ($LASTEXITCODE -ne 0 -and ($output | Out-String) -match 'divergiu') 'Push divergente não foi recusado.'

    RunGit @('checkout', '--orphan', 'rewritten')
    RunGit @('rm', '-rf', '.')
    Set-Content -LiteralPath 'replacement.txt' -Value 'rewritten'
    RunGit @('add', 'replacement.txt')
    Commit 'Rewritten history' 'me@example.com' '2024-01-08T10:00:00+00:00'
    RunGit @('push', '--force', 'origin', 'rewritten:main')
    $output = & java -jar $AppJar sync --store $store2 --web-url 'https://dev.azure.com/Org/Project/_git/Repo' --repo-id fixture 2>&1
    Assert ($LASTEXITCODE -ne 0 -and ($output | Out-String) -match 'reescrito') 'Rebase/force push não foi detectado.'
    Write-Output 'Integration tests passed.'
} finally {
    Pop-Location
    Remove-Item Env:GIT_AUTHOR_NAME,Env:GIT_AUTHOR_EMAIL,Env:GIT_AUTHOR_DATE,Env:GIT_COMMITTER_NAME,Env:GIT_COMMITTER_EMAIL,Env:GIT_COMMITTER_DATE -ErrorAction SilentlyContinue
}
$global:LASTEXITCODE = 0
