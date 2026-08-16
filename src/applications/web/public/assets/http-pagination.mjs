/** Reads InfraNexum pagination response headers without changing legacy response bodies. */
export function paginationMetadata(headers) {
  const read = (name) => headers?.get?.(name) ?? headers?.get?.(name.toLowerCase()) ?? null;
  const rawLimit = read('X-Page-Limit');
  const rawOffset = read('X-Next-Offset');
  const nextCursor = normalize(read('X-Next-Cursor'));
  return Object.freeze({
    limit: integerOrNull(rawLimit),
    nextCursor,
    nextOffset: integerOrNull(rawOffset),
    hasNext: nextCursor !== null || rawOffset !== null,
  });
}

function normalize(value) {
  const text = String(value ?? '').trim();
  return text || null;
}

function integerOrNull(value) {
  if (value === null || value === undefined || String(value).trim() === '') return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
}
