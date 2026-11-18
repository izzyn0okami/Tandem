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

    /** O modo hardcore mudou com o jogo já carregado: o núcleo precisa ser reiniciado. */
    void onReset();
}
