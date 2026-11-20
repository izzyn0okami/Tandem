# Registro de investigação — elementos e descrições da tela

**Data:** 2026-11-20  
**Projeto:** Tandem  
**PR de referência:** #3 — `feat/game-mode-m2-engine`  
**Estado:** Aberto — investigação pendente

## Relato observado

No Motorola Edge 30 Fusion, durante a execução de um jogo, continua aparecendo uma notificação ou descrição textual que parece identificar elementos visuais da tela (exemplo relatado: “big Mario standing in World 1-1”).

A ocorrência persistiu mesmo após a remoção do serviço `TandemOverlayService`, de seu registro no manifesto, de sua configuração de acessibilidade e dos controles correspondentes na interface.

## Objetivo

Identificar, com evidências, qual componente está produzindo a descrição ou notificação. Não atribuir a origem ao Tandem, ao RetroAchievements ou ao Android sem confirmação.

## Hipóteses a verificar

1. Serviço de acessibilidade do Android ou de aplicativo de terceiros ativo no aparelho.
2. Serviço de acessibilidade remanescente do Lawnchair ou outro componente do launcher.
3. Notificação produzida pelo RetroAchievements, pelo núcleo Libretro ou pelo próprio jogo.
4. Recurso do sistema que reconhece/descreve imagens ou conteúdo visual.
5. Outro aplicativo com permissão para observar notificações, janelas ou conteúdo da tela.

## Roteiro de apuração

- [ ] Registrar o texto exato da notificação, seu ícone, horário e momento em que aparece.
- [ ] Identificar o aplicativo responsável pela notificação nas informações da própria notificação ou no histórico de notificações do Android.
- [ ] Verificar quais serviços de acessibilidade estão habilitados no aparelho e identificar seus respectivos pacotes.
- [ ] Comparar o comportamento com o Tandem aberto, fechado e com o jogo em execução fora do modo jogos, quando possível.
- [ ] Consultar logs do Android no mesmo intervalo do evento, procurando o pacote emissor e eventos de notificação/acessibilidade.
- [ ] Verificar separadamente se o evento coincide com notificações de conquista, Rich Presence ou mensagens do RetroAchievements.
- [ ] Só depois de identificar o responsável, decidir se é necessário alterar código, manifesto, permissões ou configurações.

## Evidências a coletar

- Captura de tela da notificação, incluindo ícone e texto completos.
- Nome do aplicativo exibido como emissor e detalhes disponíveis ao tocar/expandir a notificação.
- Horário aproximado e sequência de ações que fazem a notificação aparecer.
- Lista dos serviços de acessibilidade habilitados, com nomes dos aplicativos.
- Trecho de logcat restrito ao intervalo do evento, se disponível.

## Cuidados

- Não registrar imagens da tela, textos privados, credenciais ou conteúdo pessoal em logs de diagnóstico.
- Preferir metadados: pacote emissor, classe do serviço, tipo de evento, horário e identificador da notificação.
- Não remover nem desativar outros serviços do Lawnchair ou do Android sem evidência de que sejam a origem.
- Preservar a integração RetroAchievements e o motor Libretro durante a investigação.

## Critério para encerrar

A investigação será considerada concluída quando houver evidência que identifique o pacote/componente responsável e um teste reproduzível que confirme a origem. A correção deverá ser direcionada somente ao componente identificado.

## Extensão do log do Tandem — metadados de tela e sobreposições

**Implementação inicial:** commit `1940189eada82068ba07e318b36721a85d520ba8`.

O exportador do `GameLog` agora acrescenta um retrato dos seguintes indicadores no momento da exportação:

- Se o próprio Tandem tem permissão para desenhar sobre outros aplicativos.
- Componentes/serviços de acessibilidade habilitados no Android.
- Componentes/serviços ouvintes de notificações habilitados no Android.

Esses dados são metadados de configuração do sistema. O diagnóstico não captura pixels, não lê o texto de outras janelas e não tenta enumerar conteúdo privado.

### Limitação técnica importante

O Android não disponibiliza uma API pública geral que permita a um aplicativo comum listar todas as janelas e sobreposições atualmente exibidas por outros processos. A presença de um serviço na lista de acessibilidade ou de ouvintes de notificação também não prova que ele esteja ativo naquele instante nem que tenha gerado a notificação observada.

Portanto, esta primeira etapa ajuda a identificar candidatos e permissões, mas não atribui automaticamente a origem. Para correlacionar o evento, o relatório ainda deve ser combinado com o horário exato, o emissor da notificação e, se possível, um trecho de logcat do mesmo intervalo.

### Como exportar

Abra a área de diagnóstico/logs do Modo Jogo e use a ação existente de exportação do registro. O arquivo `tandem-log.txt` incluirá a seção **Diagnóstico de tela/sobreposições** no cabeçalho, além dos eventos já registrados.

### Próxima etapa de investigação

Depois de receber o arquivo exportado, conferir os componentes habilitados e cruzá-los com o emissor da notificação e os eventos do sistema. Só então decidir se é necessário instrumentar um componente específico ou solicitar uma coleta adicional controlada pelo usuário.

## Resultado da inspeção do código — Rich Presence identificado

**Data:** 2026-11-20  
**Branch examinada:** `feat/game-mode-m2-engine`  
**Commit examinado:** `e11cd4acc4d97983e650bf532c220e8768f16b42`

O relato foi esclarecido: a descrição aparece como uma sobreposição flutuante dentro do jogo, no mesmo estilo visual dos avisos de Hardcore Mode e de conquistas desbloqueadas, e o texto acompanha a tela de título, comandos, power-ups e morte do personagem.

A inspeção do código identificou uma correspondência direta com o Rich Presence do RetroAchievements:

1. Em `RetroAchievements.kt`, quando `onLoadGameResult(success = true)` confirma que o jogo foi carregado e que o processamento de recursos é necessário, `startRichPresenceUpdates()` é iniciado.
2. Essa rotina consulta `view.getAchievementsRichPresence()` em intervalo de 2 segundos. A consulta é feita no motor oficial `rc_client`; o Tandem não implementa um parser próprio de Rich Presence.
3. Quando a mensagem muda, o callback `onRichPresence` atualiza o estado `raRichPresence` em `GameSessionActivity.kt`.
4. A função Compose `RichPresenceOverlay`, em `RaBadge.kt`, apresenta o texto de forma persistente na parte inferior central da tela. Ao encerrar a sessão, `RaSession.stop()` limpa a mensagem.

### Separação entre os avisos

- **Rich Presence:** descrição dinâmica do estado atual do jogo; é o candidato que corresponde ao relato de título, comandos, power-ups e morte.
- **Conquista desbloqueada:** evento `onAchievementTriggered` chama `onUnlock`, que alimenta `AchievementOverlay`; é um aviso temporário separado.
- **Hardcore/Softcore e estado inicial das conquistas:** mensagens da `raNoticeQueue`, exibidas por `GameMessageOverlay`. O status `TRACKING` recebe a quantidade desbloqueada e o total.
- **Indicador de sincronização:** `RaSyncIndicator`, independente do Rich Presence.

### Conclusão e validação restante

A evidência do código permite identificar o mecanismo como **Rich Presence do RetroAchievements apresentado pelo Tandem**, e não como leitura visual por acessibilidade. A descrição detalhada é calculada pelo `rc_client` a partir dos dados de memória do jogo e dos scripts de Rich Presence associados ao título.

A confirmação final no aparelho pode ser feita observando se o texto descrito muda aproximadamente em tempo real e desaparece ao encerrar a sessão do jogo. Não há justificativa técnica, com as evidências atuais, para remover outros serviços de acessibilidade, ouvintes de notificação ou componentes do Android.

Nenhuma alteração funcional foi necessária para esta identificação; este registro documenta o resultado da auditoria.


## Controle de visibilidade do Rich Presence

**Implementação:** controle persistente nas configurações do Modo Jogo, seção Online.

- A opção **Exibir Rich Presence durante o jogo** fica ativada por padrão, preservando o comportamento anterior.
- Ao desativá-la, o Tandem oculta somente `RichPresenceOverlay`; a consulta periódica ao `rc_client` e a atualização de `raRichPresence` continuam ativas.
- A preferência é salva em `SharedPreferences` e reaplicada ao iniciar cada sessão.
- Durante a partida, o menu de pausa também permite ocultar ou reexibir a descrição imediatamente, sem reiniciar o jogo; a alteração atualiza e persiste a mesma preferência.
- Avisos de conquistas, indicadores de progresso, placares e sincronização não são afetados.

A alteração não adiciona um parser próprio nem altera o ciclo de vida da sessão RetroAchievements.

## Validação final — controle de visibilidade do Rich Presence

**Data:** 2026-11-20  
**PR:** #3 — `feat/game-mode-m2-engine`  
**Commit de código validado pela CI:** `c9bcc4381957d67cfcb16d4b7e4b5e1aaacd204c`

### CI #312

A execução [CI #312](https://github.com/izzyn0okami/Tandem/actions/runs/44830859924) foi concluída com sucesso:

- `spotlessCheck`: aprovado.
- `assembleLawnWithQuickstepGithubDebug`: compilação aprovada.
- `final-status`: aprovado.
- Artefato APK gerado e disponível.

### Teste em aparelho

O usuário confirmou que os testes no telefone foram bem-sucedidos. O escopo de validação informado foi a alternância do Rich Presence pelo menu de pausa, ocultando e reexibindo a descrição durante a partida sem reiniciar o jogo.

### Revisão de regressão

Inspeção dos fluxos em `RetroAchievements.kt`, `GameSessionActivity.kt`, `GameModeSettings.kt`, `GameScreens.kt` e `RaBadge.kt`:

- A preferência é carregada ao criar a sessão e persistida em `SharedPreferences`.
- O menu de pausa atualiza o estado visual e grava a nova preferência.
- `startRichPresenceUpdates()` continua consultando `rc_client` a cada dois segundos, sem depender da visibilidade.
- O controle afeta somente a chamada visual de `RichPresenceOverlay`.
- Desbloqueios e avisos de conquistas continuam usando seus fluxos próprios.
- Indicadores de desafio e progresso, placares, avisos de conexão e `RaSyncIndicator` não são condicionados pela preferência de Rich Presence.
- O encerramento da sessão continua chamando `RaSession.stop()` e destruindo o motor nativo.

**Resultado:** nenhuma regressão relacionada ao controle de visibilidade foi identificada na revisão direcionada. Os testes no aparelho e a CI #312 foram aprovados.

**Limite da validação:** a revisão de código não substitui testes específicos de todos os tipos de conquista, placar e cenários de reconexão de rede.

