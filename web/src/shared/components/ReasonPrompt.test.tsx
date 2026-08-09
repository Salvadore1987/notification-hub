import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '../../test/render';
import { useReasonPrompt } from './ReasonPrompt';

/**
 * FR-7.3: обоснование — заголовок X-Commhub-Reason, а отказ от модалки — отказ от действия.
 * Экран-стенд повторяет то, как хук используется на настоящих экранах (DLQ, батчи, шаблоны).
 */
function Screen({ onResult }: { readonly onResult: (reason: string | null) => void }) {
  const { reasonModal, askReason } = useReasonPrompt();
  const [asking, setAsking] = useState(false);
  return (
    <>
      <button
        disabled={asking}
        onClick={() => {
          setAsking(true);
          void askReason('Архивировать').then((reason) => {
            setAsking(false);
            onResult(reason);
          });
        }}
      >
        act
      </button>
      {reasonModal}
    </>
  );
}

describe('useReasonPrompt', () => {
  it('resolves with the typed justification, trimmed', async () => {
    const onResult = vi.fn();
    renderWithProviders(<Screen onResult={onResult} />);

    await userEvent.click(screen.getByRole('button', { name: 'act' }));
    await userEvent.type(await screen.findByRole('textbox'), '  дубль после инцидента  ');
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    await waitFor(() => expect(onResult).toHaveBeenCalledWith('дубль после инцидента'));
  });

  it('resolves with an empty string when the operator confirms without a reason', async () => {
    const onResult = vi.fn();
    renderWithProviders(<Screen onResult={onResult} />);

    await userEvent.click(screen.getByRole('button', { name: 'act' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Подтвердить' }));

    await waitFor(() => expect(onResult).toHaveBeenCalledWith(''));
  });

  it('resolves with null when the operator changes their mind — nothing is sent', async () => {
    const onResult = vi.fn();
    renderWithProviders(<Screen onResult={onResult} />);

    await userEvent.click(screen.getByRole('button', { name: 'act' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Отмена' }));

    await waitFor(() => expect(onResult).toHaveBeenCalledWith(null));
  });

  it('shows the action being justified and starts empty every time', async () => {
    const onResult = vi.fn();
    renderWithProviders(<Screen onResult={onResult} />);

    await userEvent.click(screen.getByRole('button', { name: 'act' }));
    expect(await screen.findByText('Архивировать')).toBeInTheDocument();
    await userEvent.type(screen.getByRole('textbox'), 'первый раз');
    await userEvent.click(screen.getByRole('button', { name: 'Отмена' }));

    await userEvent.click(screen.getByRole('button', { name: 'act' }));
    await waitFor(() => expect(screen.getByRole('textbox')).toHaveValue(''));
  });
});
