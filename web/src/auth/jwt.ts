/**
 * Чтение claim'ов access-токена — того самого документа, по которому роли решает backend.
 *
 * <p>Раньше первым источником был профиль id-токена, а payload access-токена — запасным; с формой
 * входа в панели id-токен читать незачем: роль и имя оператора нужны те же, что увидит
 * `@PreAuthorize`, а увидит он access-токен.
 *
 * <p>Подпись здесь не проверяется и проверяться не должна: это разбор для отрисовки меню, решение
 * же принимает сервер. Битый токен — пустой набор claim'ов, а не исключение на весь рендер.
 */
export function tokenClaims(accessToken: string): Record<string, unknown> {
  try {
    const payload = accessToken.split('.')[1];
    const json = new TextDecoder().decode(
      Uint8Array.from(atob(payload.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0)),
    );
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return {};
  }
}
