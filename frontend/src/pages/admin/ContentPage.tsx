import { useMemo, useState } from 'react';
import { contentApi } from '../../api/content';
import type { Content, ContentInput, ContentStatus, ContentType } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DataTable } from '../../components/DataTable';
import { ErrorAlert } from '../../components/ErrorAlert';
import { SelectField, TextAreaField, TextField, enumOptions } from '../../components/FormFields';
import { FormModal } from '../../components/FormModal';
import { PageHeader } from '../../components/PageHeader';
import { ReasonDialog } from '../../components/ReasonDialog';
import { StatusBadge } from '../../components/StatusBadge';
import { useAsync } from '../../hooks/useAsync';
import { UPLOAD_RULES, sniffMime, validateUpload } from '../../utils/contentUpload';
import { formatDateTime, isoToLocalInput, localInputToIso } from '../../utils/format';

const TYPES: ContentType[] = ['ANNOUNCEMENT', 'IMAGE', 'VIDEO'];
const STATUSES: ContentStatus[] = ['DRAFT', 'PENDING_APPROVAL', 'PUBLISHED', 'REJECTED', 'ARCHIVED'];

/** Scope options: national (none), a region from the user's assignments, or one of the user's sites. */
function useScopeOptions() {
  const { me } = useAuth();
  return useMemo(() => {
    const regions = new Map<string, string>();
    me?.assignments.forEach((a) => a.regionId && regions.set(a.regionId, a.regionName ?? a.regionId));
    return [
      ...Array.from(regions, ([id, name]) => ({ value: `region:${id}`, label: `Region: ${name}` })),
      ...(me?.sites ?? []).map((s) => ({ value: `site:${s.id}`, label: `Site: ${s.code} — ${s.name}` })),
    ];
  }, [me]);
}

function scopeToFields(scope: string): Pick<ContentInput, 'siteId' | 'regionId'> {
  const [kind, id] = scope.split(':');
  if (kind === 'site') return { siteId: id };
  if (kind === 'region') return { regionId: id };
  return {};
}

function ContentForm({ item, onClose, onSaved }: { item: Content | null; onClose: () => void; onSaved: () => void }) {
  const scopeOptions = useScopeOptions();
  const [title, setTitle] = useState(item?.title ?? '');
  const [type, setType] = useState<ContentType>(item?.type ?? 'ANNOUNCEMENT');
  const [body, setBody] = useState(item?.body ?? '');
  const [scope, setScope] = useState(item?.siteId ? `site:${item.siteId}` : item?.regionId ? `region:${item.regionId}` : '');
  const [startsAt, setStartsAt] = useState(isoToLocalInput(item?.startsAt));
  const [endsAt, setEndsAt] = useState(isoToLocalInput(item?.endsAt));
  const [displaySeconds, setDisplaySeconds] = useState(item?.displaySeconds ?? 10);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const isEdit = !!item;

  /** Checks declared type/size and then the real type from the file's magic bytes. */
  const onFile = async (f: File | null) => {
    setFile(f);
    if (!f) return setFileError(null);
    const basic = validateUpload(f, type);
    if (basic) return setFileError(basic);
    const head = new Uint8Array(await f.slice(0, 16).arrayBuffer());
    const sniffed = sniffMime(head);
    setFileError(sniffed && UPLOAD_RULES[type as 'IMAGE' | 'VIDEO'].mimeTypes.includes(sniffed) ? null : 'File content does not match an allowed format.');
  };

  return (
    <FormModal title={isEdit ? `Edit "${item.title}"` : 'New content'} size="lg" onClose={onClose}
      validate={() => {
        if (!title.trim()) return 'Title is required.';
        if (type === 'ANNOUNCEMENT' && !body.trim()) return 'Announcement text is required.';
        if (displaySeconds < 1) return 'Display time must be at least 1 second.';
        if (startsAt && endsAt && new Date(endsAt) <= new Date(startsAt)) return 'End must be after start.';
        // Media can only be attached when creating (PUT takes JSON only).
        if (!isEdit && type !== 'ANNOUNCEMENT') return fileError ?? validateUpload(file, type);
        return null;
      }}
      onSubmit={async () => {
        const input: ContentInput = {
          title: title.trim(),
          type,
          body: body.trim() || undefined,
          ...scopeToFields(scope),
          startsAt: localInputToIso(startsAt),
          endsAt: localInputToIso(endsAt),
          displaySeconds,
        };
        if (isEdit) await contentApi.update(item.id, input);
        else await contentApi.create(input, type === 'ANNOUNCEMENT' ? null : file);
        onSaved();
      }}>
      {(fe) => (
        <>
          <TextField label="Title" required value={title} onChange={setTitle} error={fe('title')} />
          <div className="row">
            <SelectField className="col-md-6 mb-3" label="Type" value={type} disabled={isEdit}
              onChange={(v) => { setType(v as ContentType); setFile(null); setFileError(null); }} options={enumOptions(TYPES)} />
            <SelectField className="col-md-6 mb-3" label="Audience" value={scope} onChange={setScope}
              placeholder="National (all sites in my scope)" options={scopeOptions} error={fe('siteId') ?? fe('regionId')} />
          </div>
          <TextAreaField label={type === 'ANNOUNCEMENT' ? 'Announcement text' : 'Caption (optional)'} required={type === 'ANNOUNCEMENT'}
            value={body} onChange={setBody} error={fe('body')} />
          {!isEdit && type !== 'ANNOUNCEMENT' && (
            <div className="mb-3">
              <label htmlFor="content-file" className="form-label">
                {type === 'IMAGE' ? 'Image (PNG, JPEG, WebP · max 10 MB)' : 'Video (MP4, WebM · max 200 MB)'} <span aria-hidden="true">*</span>
              </label>
              <input id="content-file" type="file" className={`form-control ${fileError ? 'is-invalid' : ''}`}
                accept={UPLOAD_RULES[type].accept} onChange={(e) => void onFile(e.target.files?.[0] ?? null)} />
              {fileError && <div className="invalid-feedback">{fileError}</div>}
            </div>
          )}
          <div className="row">
            <TextField className="col-md-4 mb-3" label="Starts" type="datetime-local" value={startsAt} onChange={setStartsAt} error={fe('startsAt')} />
            <TextField className="col-md-4 mb-3" label="Ends" type="datetime-local" value={endsAt} onChange={setEndsAt} error={fe('endsAt')} />
            <TextField className="col-md-4 mb-3" label="Display seconds" type="number" min={1} required value={displaySeconds}
              onChange={(v) => setDisplaySeconds(Number(v))} error={fe('displaySeconds')} />
          </div>
        </>
      )}
    </FormModal>
  );
}

type Action = { kind: 'submit' | 'approve' | 'archive' | 'reject'; item: Content } | null;

export function ContentPage() {
  const { me, can } = useAuth();
  const canEdit = can('CONTENT_EDIT');
  const canApprove = can('CONTENT_APPROVE');
  const [siteId, setSiteId] = useState('');
  const [status, setStatus] = useState<ContentStatus | ''>('');
  const list = useAsync(() => contentApi.list({ siteId, status }), [siteId, status]);
  const [edit, setEdit] = useState<Content | 'new' | null>(null);
  const [action, setAction] = useState<Action>(null);
  const reload = () => void list.reload();
  // ASSUMPTION: Content.createdBy carries the author's username (the contract does not specify).
  const isAuthor = (c: Content) => c.createdBy === me?.username || c.createdBy === me?.id;
  // Super Administrators may approve their own content (the server audits it as a self-approval);
  // everyone else needs a second person.
  const isSuperAdmin = !!me?.assignments.some((a) => a.roleCode === 'SUPER_ADMIN');
  const blockedAsAuthor = (c: Content) => isAuthor(c) && !isSuperAdmin;

  return (
    <>
      <PageHeader title="Content" subtitle="Announcements and media for kiosks and queue displays"
        actions={canEdit && <button type="button" className="btn btn-primary" onClick={() => setEdit('new')}>New content</button>} />
      <div className="row g-2 mb-2">
        <SelectField className="col-md-4" label="Site" value={siteId} onChange={setSiteId} placeholder="All in my scope"
          options={(me?.sites ?? []).map((s) => ({ value: s.id, label: s.name }))} />
        <SelectField className="col-md-4" label="Status" value={status} onChange={(v) => setStatus(v as ContentStatus | '')}
          placeholder="Any status" options={enumOptions(STATUSES)} />
      </div>
      <ErrorAlert error={list.error} />
      <DataTable caption="Content items" loading={list.loading} rows={list.data} rowKey={(c) => c.id}
        columns={[
          { key: 'title', header: 'Title', render: (c) => (
            <>
              {c.title}
              {c.origin === 'CENTRAL' && <span className="badge text-bg-light text-dark border ms-2">From central office</span>}
              {c.rejectionReason && c.status === 'REJECTED' && <div className="small text-danger">Rejected: {c.rejectionReason}</div>}
              {c.status === 'PENDING_APPROVAL' && canApprove && blockedAsAuthor(c) && (
                <div className="small text-body-secondary">
                  Awaiting another approver: you created this item, and content needs a second person to publish it.
                </div>
              )}
            </>
          ) },
          { key: 'type', header: 'Type' },
          { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
          { key: 'window', header: 'Schedule', render: (c) => `${formatDateTime(c.startsAt)} → ${formatDateTime(c.endsAt)}` },
          { key: 'displaySeconds', header: 'Secs', className: 'text-end' },
          { key: 'createdBy', header: 'Author' },
          { key: 'media', header: 'Media', render: (c) => (c.mediaUrl && c.status === 'PUBLISHED'
            ? <a href={c.mediaUrl} target="_blank" rel="noreferrer">View</a> : c.mimeType ?? '—') },
          { key: 'actions', header: <span className="visually-hidden">Actions</span>, className: 'text-end text-nowrap',
            render: (c) => {
              // Content replicated from central is managed there and is read-only on this node.
              if (c.origin === 'CENTRAL') return <span className="small text-body-secondary">Managed centrally</span>;
              const editable = c.status === 'DRAFT' || c.status === 'REJECTED';
              return (
                <div className="btn-group btn-group-sm">
                  {canEdit && editable && <button type="button" className="btn btn-outline-primary" onClick={() => setEdit(c)}>Edit</button>}
                  {canEdit && editable && <button type="button" className="btn btn-outline-primary" onClick={() => setAction({ kind: 'submit', item: c })}>Submit</button>}
                  {canApprove && c.status === 'PENDING_APPROVAL' && (
                    <>
                      <button type="button" className="btn btn-outline-success" disabled={blockedAsAuthor(c)}
                        title={blockedAsAuthor(c) ? 'Authors cannot approve their own content'
                          : isAuthor(c) ? 'You created this item; your approval is recorded as a self-approval' : undefined}
                        onClick={() => setAction({ kind: 'approve', item: c })}>Approve</button>
                      <button type="button" className="btn btn-outline-danger" onClick={() => setAction({ kind: 'reject', item: c })}>Reject</button>
                    </>
                  )}
                  {(canEdit || canApprove) && c.status !== 'ARCHIVED' && (
                    <button type="button" className="btn btn-outline-secondary" onClick={() => setAction({ kind: 'archive', item: c })}>Archive</button>
                  )}
                </div>
              );
            } },
        ]} />

      {edit && <ContentForm item={edit === 'new' ? null : edit} onClose={() => setEdit(null)} onSaved={reload} />}
      {action?.kind === 'reject' && (
        <ReasonDialog title={`Reject "${action.item.title}"`} variant="danger" confirmLabel="Reject"
          onSubmit={(r) => contentApi.reject(action.item.id, r).then(reload)} onClose={() => setAction(null)} />
      )}
      {action && action.kind !== 'reject' && (
        <ConfirmDialog
          title={`${action.kind.charAt(0).toUpperCase()}${action.kind.slice(1)} "${action.item.title}"`}
          message={{
            submit: 'Send this item for approval?',
            approve: 'Publish this item to kiosks and displays in its audience?',
            archive: 'Archive this item? It will no longer be shown.',
          }[action.kind]}
          variant={action.kind === 'archive' ? 'warning' : 'primary'}
          onConfirm={() => {
            const { kind, item } = action;
            const call = kind === 'submit' ? contentApi.submit : kind === 'approve' ? contentApi.approve : contentApi.archive;
            return call(item.id).then(reload);
          }}
          onClose={() => setAction(null)} />
      )}
    </>
  );
}
