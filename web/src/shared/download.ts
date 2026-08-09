/**
 * Сохранение выгрузки на диск. Содержимое приходит из типизированного клиента (тот же запрос,
 * что и на экране, — §11.2), здесь только blob и клик: BOM для Excel ставит backend.
 */
export function saveTextFile(content: string, filename: string, mime = 'text/csv'): void {
  const url = URL.createObjectURL(new Blob([content], { type: mime }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/** Читает выбранный оператором CSV-файл как текст — для импортов шаблонов и suppression list. */
export function readFileText(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(reader.error ?? new Error('read failed'));
    reader.readAsText(file);
  });
}
