# Tandem

Um launcher para Android com um **modo jogos** inspirado nos antigos Sony Ericsson Xperia Play e Nokia N-Gage: interface em tela cheia no estilo SteamOS, feita para jogar com controle físico. Projeto pessoal, baseado no [Lawnchair](https://github.com/LawnchairLauncher/lawnchair).

## O que o modo jogos faz

- **Biblioteca única:** jogos do aparelho, do servidor [RomM](https://romm.app) e jogos Android numa só grade. Se o jogo está no aparelho, o botão é **Jogar**; se não, **Baixar**. Jogos com várias versões (regiões, revisões) deixam você escolher qual baixar.
- **Emulação embutida** (libretro) para NES, SNES, Game Boy, GBA, Mega Drive, Master System, Game Gear, Mega-CD, N64, PlayStation, PC Engine, Atari, Saturn, Dreamcast, DS, PSP e outros. Vários núcleos por console, com as opções de cada núcleo no menu de pausa, saves de bateria e estados salvos.
- **Emuladores externos** para PS2, GameCube, Wii, Switch, 3DS e Wii U, e integração com o GameNative para jogos de PC.
- **RetroAchievements:** login, lista de conquistas por jogo, modo hardcore e aviso ao desbloquear (com ícone e som).
- **Capas automáticas:** do RomM, do acervo de miniaturas do Libretro, do SteamGridDB (com chave própria) ou geradas no aparelho.
- **Feito para controle:** navegação por direcional, L1/R1 troca de aba, L2/R2 troca de console, Y vai aos consoles, X atualiza; BIOS, saves e imagens em pastas à sua escolha; modo paisagem em tela cheia.
- **Diagnóstico:** exportação de log com o motivo de travamentos de núcleos.

## Baixar e atualizar

Baixe o APK mais recente na página de [Releases](../../releases/tag/tandem-latest) (arquivo `Tandem.apk`). Para atualizar, instale o novo APK por cima do atual: os builds usam sempre a mesma chave de assinatura e a versão sobe a cada build, então configurações e jogos são mantidos.

Aviso: os builds são de teste (debug) e a chave de assinatura é pública, no repositório. Não use este APK como se fosse uma versão oficial.

## Estado do projeto

O acompanhamento detalhado (o que funciona, o que está em teste e o que falta) fica nos documentos do projeto. O desenvolvimento acontece nos ramos `feat/game-mode-m2-engine` e `game-mode-m2-dev`.

## Créditos

- [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) e Launcher3 (AOSP): base do launcher.
- [libretrodroid](https://github.com/Swordfish90/LibretroDroid) e os núcleos do [libretro](https://www.libretro.com): emulação.
- [RomM](https://romm.app): servidor de jogos. [RetroAchievements](https://retroachievements.org): conquistas. [SteamGridDB](https://www.steamgriddb.com): capas.
- Acervo de miniaturas do [Libretro](https://thumbnails.libretro.com).

Os nomes e marcas de consoles pertencem aos seus donos; este projeto não é afiliado a nenhum deles. Veja `LICENSE.txt` para a licença do código.

O README original do Lawnchair continua disponível no [repositório de origem](https://github.com/LawnchairLauncher/lawnchair#readme).
