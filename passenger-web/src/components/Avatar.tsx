
export function Avatar({ name, onBoard = false, me = false }: { name: string; onBoard?: boolean; me?: boolean }) {
  const initial = Array.from(name.trim())[0] ?? "?";
  return (
    <span className={`avatar${onBoard ? " avatar--on" : ""}${me ? " avatar--me" : ""}`} aria-hidden="true">{initial}</span>
  );
}
