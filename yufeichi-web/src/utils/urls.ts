// Only local, normalized uploaded images are rendered. Remote images are never fetched.
export function imageUrl(value: string | null | undefined): string | undefined {
  return value &&
    /^\/uploads\/(?:avatar|article|project|other)\/[a-f0-9-]+\.(?:png|jpg)$/.test(
      value,
    )
    ? value
    : undefined;
}
export function externalUrl(
  value: string | null | undefined,
): string | undefined {
  if (
    !value ||
    !/^https?:\/\//i.test(value) ||
    /[\s\\\u0000-\u001f\u007f]/.test(value)
  )
    return undefined;
  try {
    const url = new URL(value);
    return !url.username &&
      !url.password &&
      ["http:", "https:"].includes(url.protocol)
      ? url.href
      : undefined;
  } catch {
    return undefined;
  }
}
export function linkUrl(value: string): string | undefined {
  if (
    /^\/(?:articles|projects)(?:\/[1-9]\d*)?$/.test(value) ||
    value === "/" ||
    value === "/about"
  )
    return value;
  return externalUrl(value);
}
