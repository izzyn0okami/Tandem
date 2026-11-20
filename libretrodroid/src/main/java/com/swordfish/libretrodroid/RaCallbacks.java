/*
 *     Copyright (C) 2024  Argosy
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.swordfish.libretrodroid;

/**
 * Chamado de volta pelo motor nativo da RetroAchievements (rc_client), através de
 * LibretroDroid.raSetCallbacks. Todo método pode ser chamado em qualquer thread (a que chamou o
 * native correspondente: login/carregar jogo numa thread de fundo, ou a do emulador a cada
 * quadro) -- quem implementa decide para onde despachar.
 */
public interface RaCallbacks {

    /**
     * O rc_client precisa fazer uma chamada HTTP; quem implementa deve executá-la (com o OkHttp,
     * por exemplo) em segundo plano e, quando terminar (sucesso, erro HTTP, ou falha de rede),
     * chamar LibretroDroid.raDeliverServerResponse(requestId, ...) com o mesmo requestId. Nunca
     * bloquear dentro deste método.
     */
    void onServerCall(int requestId, String url, String postData, String contentType);

    /** Resultado do login (raBeginLoginWithToken). */
    void onLoginResult(boolean success, String errorMessage);

    /** Resultado de carregar o jogo (raBeginLoadGame). */
    void onLoadGameResult(boolean success, String errorMessage);

    /** Uma conquista foi desbloqueada (o rc_client já cuida de enviar ao site, com nova tentativa se preciso). */
    void onAchievementTriggered(int achievementId);

    /** Uma chamada ao servidor falhou de forma definitiva (não vai ser tentada de novo). */
    void onServerError(String api, String message);

    /** O servidor ficou indisponível; há atualizações pendentes de sincronização. */
    void onDisconnected();

    /** A conexão voltou e as atualizações pendentes foram sincronizadas. */
    void onReconnected();

    /** O modo hardcore mudou com o jogo já carregado: o núcleo precisa ser reiniciado. */
    void onReset();

    // --- Indicadores e avisos extras, no padrão de overlay da própria RetroAchievements
    // (https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration) ----------------

    /** Ícone de "desafio ativo" para uma conquista (ex.: "não tome dano"); várias podem aparecer ao mesmo tempo. */
    void onChallengeIndicatorShow(int achievementId, String badge);

    /** O desafio da conquista não está mais ativo: esconder o ícone correspondente. */
    void onChallengeIndicatorHide(int achievementId);

    /** Indicador de progresso (ex.: "7 de 10"); só um por vez, chamado também quando o progresso muda. */
    void onProgressIndicatorShow(int achievementId, String title, String badge, String measuredProgress, float measuredPercent);

    /** Esconder o indicador de progresso atual. */
    void onProgressIndicatorHide();

    /** Uma tentativa de placar (leaderboard) começou. */
    void onLeaderboardStarted(int leaderboardId, String title, String description);

    /** Uma tentativa de placar não foi validada. */
    void onLeaderboardFailed(int leaderboardId, String title);

    /** Uma tentativa de placar foi enviada ao site. */
    void onLeaderboardSubmitted(int leaderboardId, String title, String score);

    /** Valor ao vivo de um placar sendo acompanhado (ex.: um cronômetro); chamado também a cada atualização. */
    void onLeaderboardTrackerUpdate(int trackerId, String display);

    /** O placar não está mais sendo acompanhado: esconder o valor correspondente. */
    void onLeaderboardTrackerHide(int trackerId);

    /** Todas as conquistas do jogo (ou de um subconjunto/DLC) foram obtidas. JSON: title, badgeUrl, hardcore, subset. */
    void onGameCompleted(String json);
}
