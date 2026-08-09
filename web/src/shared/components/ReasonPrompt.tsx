import { Input, Modal } from 'antd';
import { useCallback, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Обоснование действия для заголовка X-Commhub-Reason (FR-7.3): одна модалка на экран,
 * промис на вызов. `null` — оператор передумал и действие не выполняется; пустая строка —
 * подтвердил без обоснования (заголовок опционален, аудит запишет действие без причины).
 */
export function useReasonPrompt(): {
  readonly reasonModal: ReactNode;
  readonly askReason: (title: string) => Promise<string | null>;
} {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('');
  const [text, setText] = useState('');
  const resolveRef = useRef<(reason: string | null) => void>(() => {});

  const askReason = useCallback((prompt: string) => {
    setTitle(prompt);
    setText('');
    setOpen(true);
    return new Promise<string | null>((resolve) => {
      resolveRef.current = resolve;
    });
  }, []);

  const finish = (reason: string | null) => {
    setOpen(false);
    resolveRef.current(reason);
  };

  const reasonModal = (
    <Modal
      title={title}
      open={open}
      onOk={() => finish(text.trim())}
      onCancel={() => finish(null)}
      okText={t('common.confirm')}
      cancelText={t('common.cancel')}
      destroyOnClose
    >
      <Input.TextArea
        rows={3}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder={t('common.reasonPlaceholder')}
      />
    </Modal>
  );

  return { reasonModal, askReason };
}
