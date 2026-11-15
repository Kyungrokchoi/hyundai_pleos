
type P = { size?: number; className?: string };
const base = (size: number, className?: string) => ({
  className, width: size, height: size, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2.4,
  strokeLinecap: "round" as const, strokeLinejoin: "round" as const, "aria-hidden": true as const,
});
export const IconCheck = ({ size = 22, className }: P) => (<svg {...base(size, className)}><path d="M4 12.5l5 5L20 6.5" /></svg>);
export const IconAlert = ({ size = 22, className }: P) => (<svg {...base(size, className)}><path d="M12 4l9 16H3z" /><path d="M12 10v4.5M12 17.5v.01" /></svg>);
export const IconOffline = ({ size = 22, className }: P) => (<svg {...base(size, className)}><path d="M3 3l18 18M8.5 16a5 5 0 017 0M5 12.5a10 10 0 014-2.5M19 12.5a10 10 0 00-6-2.9M12 20v.01" /></svg>);
export const IconLock = ({ size = 22, className }: P) => (<svg {...base(size, className)}><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 018 0v3" /></svg>);
export const IconClock = ({ size = 22, className }: P) => (<svg {...base(size, className)}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>);
export const IconCopy = ({ size = 22, className }: P) => (<svg {...base(size, className)}><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V6a2 2 0 012-2h9" /></svg>);
export const IconInfo = ({ size = 22, className }: P) => (<svg {...base(size, className)}><circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 8v.01" /></svg>);
export const IconChevron = ({ size = 22, className }: P) => (<svg {...base(size, className)}><path d="M6 9l6 6 6-6" /></svg>);
export const IconArrowLeft = ({ size = 22, className }: P) => (<svg {...base(size, className)}><path d="M19 12H5M11 6l-6 6 6 6" /></svg>);
export const IconStar = ({ size = 22, className }: P) => (<svg {...base(size, className)}><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z" /></svg>);
