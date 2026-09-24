# GitRecorder

CLI em Kotlin que registra no GitHub a atividade de outros repositórios Git. Cada commit selecionado vira um commit com árvore vazia: o destino não recebe arquivos, apenas autoria, data, título e referências à origem.

## Requisitos

- Git no PATH.
- JDK 21 para executar pela JVM. Para gerar um executável nativo, use GraalVM JDK 21 com Native Image. No Windows, a compilação nativa também exige as ferramentas de C++ do Visual Studio.
- Um repositório GitHub **privado, vazio e que não seja fork**, criado para receber a atividade. Crie-o sem README ou outros arquivos iniciais. Depois do primeiro push, confirme que `main` é a branch padrão.
- Os e-mails de autor usados no filtro devem estar associados à conta GitHub que receberá as contribuições.

## Compilar e instalar

No Windows, a distribuição JVM e o JAR executável são gerados com:

```powershell
.\gradlew.bat test fatJar installDist
java -jar .\build\gitrecorder.jar --help
```

No Windows, abra o **Developer PowerShell for Visual Studio** com o compilador C++ no PATH. Então compile e instale o executável nativo no PATH do usuário:

```powershell
.\gradlew.bat nativeCompile
.\scripts\install-windows.ps1
```

O instalador copia o executável para `%LOCALAPPDATA%\Programs\GitRecorder`. Abra um novo PowerShell e execute `gitrecorder --version`. Use `-InstallDir CAMINHO` se quiser outro diretório; `-Store CAMINHO` configura um destino local diferente do padrão. O instalador não precisa de um destino já inicializado.

No Linux/macOS, use `./gradlew test installDist` e execute `build/install/gitrecorder/bin/gitrecorder`. Também é possível usar `./gradlew fatJar` seguido de `java -jar build/gitrecorder.jar`.

## Configuração inicial

Crie primeiro um repositório privado e vazio no GitHub. As URLs e os e-mails abaixo são apenas exemplos:

```powershell
gitrecorder init --remote https://github.com/example-user/private-activity.git
gitrecorder email add developer@example.com
gitrecorder email add developer@work.example
gitrecorder email list
```

O destino local fica em `~/.gitrecorder/repository`. Defina `GITRECORDER_STORE` ou passe `--store DIR` em cada comando para usar outro caminho. Em outro computador, execute `init --remote` com a mesma URL: a CLI recupera branches, e-mails e estado pelos Git notes publicados.

## Sincronizar uma origem

Dentro de um clone local:

```powershell
cd C:\repos\sample-app
gitrecorder sync
gitrecorder status
gitrecorder push
```

De qualquer pasta, sem manter um clone da origem:

```powershell
gitrecorder sync https://git.example.com/team/sample-app.git
gitrecorder push
```

`sync URL` (ou `sync --url URL`) clona temporariamente sem checkout, lê os commits publicados e remove o clone ao terminar, inclusive se ocorrer um erro. `push` continua explícito.

Por padrão, `sync` consulta o HEAD remoto e usa a branch principal. `--branch NOME` seleciona outra branch, que fica arquivada fora da `main` do destino. Se um projeto usa outra branch como principal de fato, marque-a uma vez:

```powershell
gitrecorder sync --branch=android --as-primary https://dev.azure.com/example-org/example-project/_git/sample-app
gitrecorder push
```

A escolha é salva em Git notes. Nas próximas execuções, `sync --branch=android URL` integrará novos espelhos à `main` mesmo sem `--as-primary`. A branch anunciada pelo HEAD remoto passará a ser tratada como alternativa para esse projeto. `--primary` é um alias de `--as-primary`.

`--remote NOME` troca o remoto no modo de pasta local. `--repo-id ID` fixa a identidade da origem; `--web-url URL` fornece uma URL de navegação quando ela não puder ser derivada do remoto. `--no-fetch` usa refs remotas locais apenas no modo de pasta, para testes offline.

## O que é publicado

- Apenas commits presentes na branch remota publicada e cujo **e-mail de autor** esteja configurado. Alterações do diretório de trabalho e commits locais ainda não publicados ficam fora.
- Um commit de árvore vazia por commit selecionado. O título segue `repositorio-branch: título original`; nome, e-mail e data do autor são preservados.
- O corpo contém plataforma, SHA e link do commit original quando disponíveis. No Azure DevOps, mensagens como `Merged PR 42: descrição` permitem associar links de PR a commits identificáveis pelo merge. A CLI não cria PRs nem consulta a API da plataforma.
- Uma branch de destino por par repositório + branch de origem. Git notes em `refs/notes/gitrecorder` guardam o mapeamento e o último SHA examinado. Sincronizações repetidas não recriam commits. Um rebase ou force push da origem interrompe a sincronização.
- `push` envia as branches e os Git notes atomicamente e recusa divergências no destino.

Para que os commits entrem no [gráfico do perfil](https://docs.github.com/en/account-and-profile/reference/profile-contributions-reference), eles precisam estar na branch padrão do destino e usar e-mails associados à conta. O GitHub pode levar [até 24 horas](https://docs.github.com/en/account-and-profile/how-tos/contribution-settings/troubleshooting-missing-contributions) para atualizar o gráfico. As datas originais são mantidas; commits antigos aparecem no período histórico correspondente. Commits vazios não entram no [painel Contributors](https://docs.github.com/en/repositories/viewing-activity-and-data-for-your-repository/viewing-a-projects-contributors).

## Testes

```powershell
.\gradlew.bat test fatJar
.\scripts\integration-test.ps1
```

Os testes usam repositórios Git temporários criados localmente. Verificam filtro de e-mails, ordem, datas, árvores vazias, mensagens, links de PR, branches alternativas, promoção da branch principal, repetição sem duplicatas, recuperação em outro clone, exclusão de commits não publicados, rejeição de push divergente e detecção de histórico reescrito. Nenhum teste publica dados no GitHub.

## Licença

[MIT](LICENSE).
