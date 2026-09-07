import { forwardRef } from 'react';

/* ------------------------------------------------------------- Button ---- */

const BUTTON_VARIANTS = {
  primary: 'bg-brand-600 text-white hover:bg-brand-700 disabled:bg-brand-300',
  secondary:
    'bg-white text-slate-700 border border-slate-300 hover:bg-slate-50 disabled:text-slate-400',
  danger: 'bg-red-600 text-white hover:bg-red-700 disabled:bg-red-300',
  ghost: 'text-slate-600 hover:bg-slate-100 disabled:text-slate-300',
};

const BUTTON_SIZES = {
  sm: 'px-2.5 py-1.5 text-xs',
  md: 'px-4 py-2 text-sm',
  lg: 'px-5 py-2.5 text-base',
};

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  disabled,
  className = '',
  children,
  ...rest
}) {
  return (
    <button
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center gap-2 rounded-md font-medium transition-colors disabled:cursor-not-allowed ${BUTTON_VARIANTS[variant]} ${BUTTON_SIZES[size]} ${className}`}
      {...rest}
    >
      {loading && <Spinner size="sm" className="border-current" />}
      {children}
    </button>
  );
}

/* ------------------------------------------------------------ Spinner ---- */

export function Spinner({ size = 'md', className = '' }) {
  const dims = { sm: 'h-3.5 w-3.5', md: 'h-6 w-6', lg: 'h-10 w-10' }[size];
  return (
    <span
      role="status"
      aria-label="Loading"
      className={`inline-block animate-spin rounded-full border-2 border-slate-300 border-t-transparent ${dims} ${className}`}
    />
  );
}

/* --------------------------------------------------------------- Card ---- */

export function Card({ className = '', children, ...rest }) {
  return (
    <div
      className={`rounded-xl border border-slate-200 bg-white shadow-sm ${className}`}
      {...rest}
    >
      {children}
    </div>
  );
}

export function CardHeader({ title, subtitle, action, className = '' }) {
  return (
    <div className={`flex items-start justify-between gap-4 border-b border-slate-100 px-5 py-4 ${className}`}>
      <div className="min-w-0">
        <h2 className="truncate text-sm font-semibold text-slate-900">{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs text-slate-500">{subtitle}</p>}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}

export function CardBody({ className = '', children }) {
  return <div className={`px-5 py-4 ${className}`}>{children}</div>;
}

/* -------------------------------------------------------------- Badge ---- */

const BADGE_TONES = {
  slate: 'bg-slate-100 text-slate-700 ring-slate-200',
  green: 'bg-green-100 text-green-800 ring-green-200',
  red: 'bg-red-100 text-red-800 ring-red-200',
  amber: 'bg-amber-100 text-amber-800 ring-amber-200',
  blue: 'bg-blue-100 text-blue-800 ring-blue-200',
  indigo: 'bg-indigo-100 text-indigo-800 ring-indigo-200',
  violet: 'bg-violet-100 text-violet-800 ring-violet-200',
  cyan: 'bg-cyan-100 text-cyan-800 ring-cyan-200',
};

export function Badge({ tone = 'slate', className = '', children }) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${BADGE_TONES[tone] || BADGE_TONES.slate} ${className}`}
    >
      {children}
    </span>
  );
}

/* -------------------------------------------------------- Form fields ---- */

export function Field({ label, hint, error, required, children, className = '' }) {
  return (
    <label className={`block ${className}`}>
      <span className="mb-1 block text-xs font-medium text-slate-700">
        {label}
        {required && <span className="ml-0.5 text-red-500">*</span>}
      </span>
      {children}
      {hint && !error && <span className="mt-1 block text-xs text-slate-500">{hint}</span>}
      {error && <span className="mt-1 block text-xs text-red-600">{error}</span>}
    </label>
  );
}

const INPUT_CLASS =
  'w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 disabled:bg-slate-50 disabled:text-slate-500';

export const Input = forwardRef(function Input({ className = '', ...rest }, ref) {
  return <input ref={ref} className={`${INPUT_CLASS} ${className}`} {...rest} />;
});

export const Select = forwardRef(function Select({ className = '', children, ...rest }, ref) {
  return (
    <select ref={ref} className={`${INPUT_CLASS} ${className}`} {...rest}>
      {children}
    </select>
  );
});

export const Textarea = forwardRef(function Textarea({ className = '', ...rest }, ref) {
  return <textarea ref={ref} className={`${INPUT_CLASS} ${className}`} {...rest} />;
});

/* -------------------------------------------------------------- Modal ---- */

export function Modal({ open, title, onClose, children, footer, size = 'md' }) {
  if (!open) return null;
  const width = { sm: 'max-w-md', md: 'max-w-lg', lg: 'max-w-2xl', xl: 'max-w-4xl' }[size];
  return (
    <div className="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto bg-slate-900/40 p-4 sm:p-8">
      <div className={`w-full ${width} rounded-xl bg-white shadow-xl`}>
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            className="text-xl leading-none text-slate-400 hover:text-slate-700"
            aria-label="Close"
          >
            ×
          </button>
        </div>
        <div className="px-5 py-4">{children}</div>
        {footer && (
          <div className="flex justify-end gap-2 border-t border-slate-100 px-5 py-3">{footer}</div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------- States ---- */

export function LoadingState({ label = 'Loading…', className = '' }) {
  return (
    <div className={`flex flex-col items-center justify-center gap-3 py-12 text-slate-500 ${className}`}>
      <Spinner />
      <p className="text-sm">{label}</p>
    </div>
  );
}

/** Deliberately distinct from ErrorState and from a reason-code empty result. */
export function EmptyState({ title, detail, action, icon = '—', className = '' }) {
  return (
    <div className={`flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-slate-300 py-12 text-center ${className}`}>
      <span className="text-2xl text-slate-300">{icon}</span>
      <p className="text-sm font-medium text-slate-700">{title}</p>
      {detail && <p className="max-w-md text-xs text-slate-500">{detail}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry, className = '' }) {
  const parsed = typeof error === 'string' ? { message: error, status: null } : error;
  return (
    <div className={`rounded-lg border border-red-200 bg-red-50 p-5 text-center ${className}`}>
      <p className="text-sm font-semibold text-red-900">
        {parsed?.status === 403
          ? 'Not allowed'
          : parsed?.status === 404
            ? 'Not found'
            : parsed?.isNetwork
              ? 'Connection failed'
              : 'Something went wrong'}
      </p>
      <p className="mt-1 text-xs text-red-800">{parsed?.message}</p>
      {/* A 403 is a permission fact, not a transient failure — never offer a retry. */}
      {onRetry && parsed?.status !== 403 && (
        <Button variant="secondary" size="sm" className="mt-3" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}

/* -------------------------------------------------------------- Table ---- */

export function Table({ columns, rows, keyField = 'id', onRowClick, empty }) {
  if (!rows?.length) return empty || null;
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-full text-left text-sm">
        <thead>
          <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
            {columns.map((col) => (
              <th key={col.key} className={`px-4 py-2.5 font-medium ${col.className || ''}`}>
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((row) => (
            <tr
              key={row[keyField]}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              className={onRowClick ? 'cursor-pointer hover:bg-slate-50' : ''}
            >
              {columns.map((col) => (
                <td key={col.key} className={`px-4 py-3 align-middle ${col.className || ''}`}>
                  {col.render ? col.render(row) : row[col.key]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* --------------------------------------------------------------- Misc ---- */

export function StatTile({ label, value, hint, tone = 'slate' }) {
  const accent = {
    slate: 'text-slate-900', green: 'text-green-700', red: 'text-red-700',
    amber: 'text-amber-700', blue: 'text-blue-700',
  }[tone];
  return (
    <Card className="p-4">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className={`mt-1 text-2xl font-semibold ${accent}`}>{value}</p>
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
    </Card>
  );
}

export function InfoNote({ tone = 'info', title, children, className = '' }) {
  const tones = {
    info: 'border-blue-200 bg-blue-50 text-blue-900',
    warning: 'border-amber-200 bg-amber-50 text-amber-900',
    gap: 'border-slate-300 bg-slate-50 text-slate-700',
  };
  return (
    <div className={`rounded-lg border px-4 py-3 text-xs ${tones[tone]} ${className}`}>
      {title && <p className="mb-1 font-semibold">{title}</p>}
      {children}
    </div>
  );
}

export function DescriptionList({ items, className = '' }) {
  return (
    <dl className={`grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2 ${className}`}>
      {items
        .filter((it) => it)
        .map((it) => (
          <div key={it.label}>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              {it.label}
            </dt>
            <dd className="mt-0.5 text-sm text-slate-900">{it.value}</dd>
          </div>
        ))}
    </dl>
  );
}
