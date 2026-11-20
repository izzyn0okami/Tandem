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
