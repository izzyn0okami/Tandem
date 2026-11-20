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
