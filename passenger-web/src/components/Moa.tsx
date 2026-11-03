import type { CharacterOut } from "@/api/types";

type Mood = CharacterOut["mood"];

const MOOD_KO: Record<Mood, string> = { SLEEPY: "졸린 표정", HAPPY: "기분 좋은 표정", PROUD: "뿌듯한 표정" };

// Accessories mark the group level. `live` animation is off under prefers-reduced-motion.
export function Moa({ mood = "HAPPY", level = 1, size = 120, live = false, grow = false, label }: {
  mood?: Mood; level?: number; size?: number; live?: boolean; grow?: boolean; label?: string;
}) {
  const decorative = label === "";
  const aria = label ?? `모아, 레벨 ${level}, ${MOOD_KO[mood]}`;
  return (
    <svg
      className={`moa${live ? " moa--live" : ""}${grow ? " moa--grow" : ""}`}
      viewBox="0 -8 120 130" width={size} height={size * (130 / 120)}
      role={decorative ? undefined : "img"} aria-hidden={decorative ? true : undefined} aria-label={decorative ? undefined : aria}
    >
      {/* wheels, drawn first so the body overlaps them */}
      {[36, 84].map((cx) => (
        <g key={cx}>
          <circle cx={cx} cy={103} r={12} fill="#14213d" />
          <circle cx={cx} cy={103} r={4.5} fill="#f3f6f8" />
          <g className="moa__wheel-spokes" stroke="#f3f6f8" strokeWidth={2.5} strokeLinecap="round">
            <line x1={cx} y1={95.5} x2={cx} y2={98.5} />
            <line x1={cx - 6.5} y1={106.5} x2={cx - 4} y2={105} />
            <line x1={cx + 6.5} y1={106.5} x2={cx + 4} y2={105} />
          </g>
        </g>
      ))}

      {level >= 3 && (
        <g stroke="#14213d" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round">
          <line x1={60} y1={14} x2={60} y2={-2} />
          <path d="M60 -2 L76 3 L60 8 Z" fill="#0e9283" />
        </g>
      )}

      <path
        d="M60 14 C92 14 108 40 108 66 C108 90 90 102 60 102 C30 102 12 90 12 66 C12 40 28 14 60 14 Z"
        fill="#ffc83d" stroke="#14213d" strokeWidth={4} strokeLinejoin="round"
      />
      <path d="M33 42 C37 32 45 26 54 24" fill="none" stroke="#ffe9a8" strokeWidth={5} strokeLinecap="round" />

      {level >= 2 && (
        <path d="M20 86 Q60 100 100 86 L97 95 Q60 109 23 95 Z" fill="#0e9283" stroke="#14213d" strokeWidth={3} strokeLinejoin="round" />
      )}
      {level >= 4 && (
        <path d="M88 20 l2.6 5.6 6 .8 -4.4 4.2 1.1 6 -5.3-2.9 -5.3 2.9 1.1-6 -4.4-4.2 6-.8 Z" fill="#f3f6f8" stroke="#14213d" strokeWidth={2.5} strokeLinejoin="round" />
      )}

      {mood !== "SLEEPY" && (
        <g fill="#e5484d" opacity={0.35}>
          <circle cx={31} cy={68} r={6} />
          <circle cx={89} cy={68} r={6} />
        </g>
      )}

      <g className="moa__eyes" fill="none" stroke="#14213d" strokeWidth={4.5} strokeLinecap="round" strokeLinejoin="round">
        {mood === "HAPPY" && (
          <>
            <circle cx={44} cy={54} r={5.5} fill="#14213d" stroke="none" />
            <circle cx={76} cy={54} r={5.5} fill="#14213d" stroke="none" />
            <circle cx={46} cy={52} r={1.8} fill="#f3f6f8" stroke="none" />
            <circle cx={78} cy={52} r={1.8} fill="#f3f6f8" stroke="none" />
          </>
        )}
        {mood === "SLEEPY" && (
          <>
            <path d="M37 54 Q44 61 51 54" />
            <path d="M69 54 Q76 61 83 54" />
          </>
        )}
        {mood === "PROUD" && (
          <>
            <path d="M37 58 Q44 47 51 58" />
            <path d="M69 58 Q76 47 83 58" />
          </>
        )}
      </g>

      {mood === "HAPPY" && <path d="M50 70 Q60 80 70 70" fill="none" stroke="#14213d" strokeWidth={4.5} strokeLinecap="round" />}
      {mood === "SLEEPY" && <ellipse cx={60} cy={74} rx={3.5} ry={4.5} fill="#14213d" />}
      {mood === "PROUD" && (
        <>
          <path d="M47 68 Q60 90 73 68 Z" fill="#14213d" stroke="#14213d" strokeWidth={3} strokeLinejoin="round" />
          <ellipse cx={60} cy={78} rx={5} ry={3} fill="#e5484d" />
        </>
      )}
    </svg>
  );
}
