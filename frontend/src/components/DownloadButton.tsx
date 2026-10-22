import { useState } from 'react';
import { toApiError } from '../api/client';
import type { ApiErrorBody } from '../api/types';
import { saveBlob } from '../utils/download';
import { ErrorAlert } from './ErrorAlert';

interface Props {
  label: string;
  download: () => Promise<{ blob: Blob; filename: string }>;
  className?: string;
}

/** Button that fetches a file through the authenticated API client and saves it; shows errors inline. */
export function DownloadButton({ label, download, className = 'btn btn-outline-primary' }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiErrorBody | null>(null);

  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      const { blob, filename } = await download();
      saveBlob(blob, filename);
    } catch (e) {
      // Error bodies of blob requests arrive as Blobs; turn them back into the JSON error.
      const data = (e as { response?: { data?: unknown } }).response?.data;
      if (data instanceof Blob) {
        try {
          setError(JSON.parse(await data.text()) as ApiErrorBody);
          return;
        } catch {
          /* fall through to the generic conversion */
        }
      }
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button type="button" className={className} disabled={busy} onClick={() => void run()}>
        {busy ? 'Preparing…' : label}
      </button>
      {error && <div className="mt-2"><ErrorAlert error={error} onDismiss={() => setError(null)} /></div>}
    </>
  );
}
