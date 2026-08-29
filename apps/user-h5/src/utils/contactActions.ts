export function buildTelephoneHref(contact: string): string {
  const match = contact.match(/(?:\+?\d[\d\s()-]{4,}\d)/);
  if (!match) return "";
  return `tel:${match[0].replace(/[\s()]/g, "")}`;
}

function legacyCopy(text: string): boolean {
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  textarea.style.pointerEvents = "none";
  document.body.appendChild(textarea);
  textarea.select();
  textarea.setSelectionRange(0, textarea.value.length);
  const copied = document.execCommand("copy");
  textarea.remove();
  return copied;
}

export async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Ordinary LAN HTTP may expose the API but reject the operation.
    }
  }
  try {
    return legacyCopy(text);
  } catch {
    return false;
  }
}
