import { useEffect, useId, useRef, useState } from "react";

export type DialogField = {
  key: string;
  label: string;
  placeholder?: string;
  defaultValue?: string;
  required?: boolean;
};

export type ModalDialogProps = {
  open: boolean;
  title: string;
  description?: string;
  fields?: DialogField[];
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  onConfirm: (values: Record<string, string>) => void;
  onCancel: () => void;
};

export default function ModalDialog({
  open,
  title,
  description,
  fields,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  destructive = false,
  onConfirm,
  onCancel,
}: ModalDialogProps) {
  const titleId = useId();
  const [values, setValues] = useState<Record<string, string>>({});
  const firstInputRef = useRef<HTMLInputElement | null>(null);
  const confirmButtonRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const initial: Record<string, string> = {};
    for (const field of fields ?? []) {
      initial[field.key] = field.defaultValue ?? "";
    }
    setValues(initial);
    const id = requestAnimationFrame(() => {
      if (fields && fields.length > 0) {
        firstInputRef.current?.focus();
      } else {
        confirmButtonRef.current?.focus();
      }
    });
    return () => cancelAnimationFrame(id);
  }, [open, fields]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onCancel();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, onCancel]);

  if (!open) {
    return null;
  }

  const missingRequired = (fields ?? []).some(
    (field) => field.required && !(values[field.key] ?? "").trim(),
  );

  return (
    <div
      className="modal-backdrop"
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onCancel();
        }
      }}
    >
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="modal-dialog"
        onSubmit={(event) => {
          event.preventDefault();
          if (!missingRequired) {
            onConfirm(values);
          }
        }}
      >
        <h3 id={titleId} className="modal-title">
          {title}
        </h3>
        {description && <p className="modal-desc">{description}</p>}
        {(fields ?? []).map((field, idx) => (
          <label key={field.key} className="modal-field">
            <span className="modal-label">{field.label}</span>
            <input
              ref={idx === 0 ? firstInputRef : undefined}
              type="text"
              value={values[field.key] ?? ""}
              placeholder={field.placeholder}
              required={field.required}
              onChange={(event) =>
                setValues((prev) => ({ ...prev, [field.key]: event.target.value }))
              }
            />
          </label>
        ))}
        <div className="modal-actions">
          <button type="button" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button
            ref={confirmButtonRef}
            type="submit"
            className={destructive ? "danger" : "primary"}
            disabled={missingRequired}
          >
            {confirmLabel}
          </button>
        </div>
      </form>
    </div>
  );
}
