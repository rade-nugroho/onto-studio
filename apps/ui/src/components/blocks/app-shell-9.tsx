"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from "react";
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react";
import {
  Add01Icon,
  ArrowDown01Icon,
  ArrowUp02Icon,
  ArrowUpRight01Icon,
  Cancel01Icon,
  CheckListIcon,
  DatabaseIcon,
  Delete02Icon,
  File02Icon,
  Globe02Icon,
  HistoryIcon,
  LayoutTemplateIcon,
  Location01Icon,
  Mail01Icon,
  Menu01Icon,
  Mic01Icon,
  Search01Icon,
  Share08Icon,
  SidebarLeftIcon,
  SourceCodeIcon,
  SparklesIcon,
  StarIcon,
  Tick02Icon,
} from "@hugeicons/core-free-icons";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

function useScrollFade<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [edges, setEdges] = useState({ start: false, end: false });

  const update = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const { scrollTop, scrollHeight, clientHeight } = el;
    setEdges({
      start: scrollTop > 1,
      end: Math.ceil(scrollTop + clientHeight) < scrollHeight - 1,
    });
  }, []);

  useEffect(() => {
    update();
    const el = ref.current;
    const view = el?.ownerDocument.defaultView;
    if (!el || !view?.ResizeObserver) return;
    const observer = new view.ResizeObserver(update);
    observer.observe(el);
    return () => observer.disconnect();
  }, [update]);

  return { ref, edges, onScroll: update };
}

const canvas = "bg-white dark:bg-neutral-950";
const paper = "bg-white dark:bg-neutral-900";
const hoverFill = "hover:bg-neutral-900/5 dark:hover:bg-neutral-100/5";
const thumbLift =
  "shadow-[0_1px_2px_rgb(0_0_0/0.1)] dark:shadow-[0_0_0_1px_rgb(255_255_255/0.16),0_1px_2px_rgb(0_0_0/0.3)]";
const cornerSm = "rounded-[var(--rb-r-sm,6px)]";
const backdrop = "bg-neutral-900/25 dark:bg-neutral-950/60";
const text1 = "text-neutral-900 dark:text-neutral-100";
const text2 = "text-neutral-900/80 dark:text-neutral-100/80";
const text3 = "text-neutral-900/66 dark:text-neutral-100/66";
const controlEdge = "border border-neutral-900/12 dark:border-neutral-100/14";
const markEdge = "border border-neutral-900/50 dark:border-neutral-100/50";
const typeHeading = "text-[24px]/[28px] font-[450] tracking-[-0.015em]";
const typeHeadingXs = "text-[16px]/[20px] font-[450] tracking-[-0.015em]";
const typeBody = "text-[14px]/[20px] font-normal";
const typeBodySm = "text-[13px]/[18px] font-normal";
const typeLabel = "text-[14px]/[16px] font-[450]";
const typeLabelSm = "text-[12px]/[16px] font-[450] tabular-nums";
const typeCaption = "text-[12px]/[16px] font-normal tabular-nums";
const focusRing =
  "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";
const transition =
  "transition-[color,background-color,border-color,box-shadow,outline-color,opacity,scale] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] active:duration-[60ms]";
const primaryButton =
  "relative inline-flex shrink-0 cursor-pointer items-center justify-center whitespace-nowrap select-none transition-[color,background-color,border-color,box-shadow,outline-color,scale,filter] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] active:scale-[0.98] active:duration-[60ms] motion-reduce:active:scale-100 disabled:cursor-not-allowed aria-busy:cursor-progress focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))] bg-[linear-gradient(180deg,color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_84%,white),color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_94%,black))] text-[var(--rb-accent-fg,oklch(100%_0_0))] shadow-[inset_0_2px_0_-1px_rgb(255_255_255/0.2),inset_0_-2px_0_-1px_rgb(0_0_0/0.3),inset_0_0_0_1px_color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_88%,black),0_1px_1px_rgb(0_0_0/0.22),0_2px_5px_-2px_rgb(0_0_0/0.3)] hover:not-disabled:brightness-110 active:shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_88%,black),inset_0_1px_2px_rgb(0_0_0/0.42)] disabled:opacity-40 disabled:shadow-none dark:bg-[linear-gradient(180deg,color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_84%,white),color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_94%,black))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))] dark:shadow-[inset_0_2px_0_-1px_rgb(255_255_255/0.2),inset_0_-2px_0_-1px_rgb(0_0_0/0.3),inset_0_0_0_1px_color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_88%,black),0_1px_1px_rgb(0_0_0/0.66),0_2px_5px_-2px_rgb(0_0_0/0.9)] dark:active:shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_88%,black),inset_0_1px_2px_rgb(0_0_0/0.42)] dark:disabled:shadow-none";
const secondaryButton =
  "relative inline-flex shrink-0 cursor-pointer items-center justify-center whitespace-nowrap select-none transition-[color,background-color,border-color,box-shadow,outline-color,scale,filter] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] active:scale-[0.98] active:duration-[60ms] motion-reduce:active:scale-100 disabled:cursor-not-allowed aria-busy:cursor-progress focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))] border border-neutral-900/12 bg-white text-neutral-900 shadow-[0_1px_2px_-1px_rgb(0_0_0/0.12)] hover:not-disabled:border-neutral-900/32 hover:not-disabled:bg-linear-to-b hover:not-disabled:from-neutral-900/3 hover:not-disabled:to-neutral-900/3 active:shadow-[inset_0_1px_2px_rgb(0_0_0/0.12)] disabled:border-neutral-900/8 disabled:text-neutral-900/40 disabled:shadow-none dark:border-neutral-100/14 dark:bg-neutral-900 dark:text-neutral-100 dark:shadow-[0_1px_2px_-1px_rgb(0_0_0/0.36)] dark:hover:not-disabled:border-neutral-100/32 dark:hover:not-disabled:from-neutral-100/3 dark:hover:not-disabled:to-neutral-100/3 dark:active:shadow-[inset_0_1px_2px_rgb(0_0_0/0.4)] dark:disabled:border-neutral-100/8 dark:disabled:text-neutral-100/40";
const ghostButton =
  "relative inline-flex shrink-0 cursor-pointer items-center justify-center whitespace-nowrap select-none transition-[color,background-color,border-color,box-shadow,outline-color,scale,filter] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] active:scale-[0.98] active:duration-[60ms] motion-reduce:active:scale-100 disabled:cursor-not-allowed aria-busy:cursor-progress focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))] bg-transparent text-neutral-900 hover:not-disabled:bg-neutral-900/5 active:bg-neutral-900/8 aria-pressed:bg-neutral-900/9 disabled:text-neutral-900/40 dark:text-neutral-100 dark:hover:not-disabled:bg-neutral-100/5 dark:active:bg-neutral-100/8 dark:aria-pressed:bg-neutral-100/9 dark:disabled:text-neutral-100/40";
const buttonSm =
  "h-7 gap-1.5 rounded-[var(--rb-r-sm,6px)] px-3 text-[14px]/[16px] font-[450]";
const buttonMd =
  "h-8 gap-2 rounded-[var(--rb-r-sm,6px)] px-4 text-[14px]/[16px] font-[450]";
const buttonLg =
  "h-11 gap-2 rounded-[var(--rb-r-sm,6px)] px-5 text-[16px]/[20px] font-[450] tracking-[-0.01em]";
const iconButtonSm = "size-7 rounded-[var(--rb-r-sm,6px)]";
const iconButtonMd = "size-8 rounded-[var(--rb-r-sm,6px)]";
const selectCaret = "shrink-0 text-neutral-900/80 dark:text-neutral-100/80";
const badge =
  "inline-flex h-5 min-w-5 w-fit shrink-0 items-center justify-center gap-1 overflow-hidden whitespace-nowrap rounded-[var(--rb-r-xs,4px)] px-1.5 text-[12px]/[16px] font-[450] tabular-nums";
const badgeNeutral =
  "bg-neutral-900/9 dark:bg-neutral-100/9 text-neutral-900 dark:text-neutral-100";
const avatar =
  "relative inline-flex size-8 shrink-0 overflow-hidden rounded-full select-none";
const avatarFallback =
  "flex size-full items-center justify-center rounded-[inherit] border border-neutral-900/12 dark:border-neutral-100/14 bg-white dark:bg-neutral-900 text-[12px]/[16px] font-[450] tabular-nums text-neutral-900 dark:text-neutral-100";
const frame =
  "relative flex min-w-0 flex-col overflow-hidden rounded-[var(--rb-r-lg,10px)] border border-neutral-900/10 dark:border-neutral-100/14 bg-white dark:bg-neutral-900 text-neutral-900 dark:text-neutral-100";
const itemRowInteractive =
  "cursor-pointer hover:bg-neutral-900/5 dark:hover:bg-neutral-100/5 active:bg-neutral-900/8 dark:active:bg-neutral-100/8";
const menuPopup =
  "min-w-48 max-w-[min(480px,calc(100vw-2rem))] overflow-x-hidden overflow-y-auto overscroll-contain rounded-[var(--rb-r-lg,10px)] border border-neutral-900/10 dark:border-neutral-100/14 bg-white dark:bg-neutral-900 p-1 text-neutral-900 dark:text-neutral-100 outline-none shadow-[0_12px_24px_-12px_rgb(0_0_0/0.2)] dark:shadow-[0_12px_24px_-12px_rgb(0_0_0/0.6)]";
const menuItem =
  "relative flex min-h-8 w-full cursor-default items-center gap-2 rounded-[var(--rb-r-sm,6px)] px-2 py-1.5 text-left text-[14px]/[16px] font-[450] text-neutral-900 outline-none select-none [&_svg]:shrink-0 [&_svg]:text-neutral-900/80 hover:bg-neutral-900/5 focus-visible:bg-neutral-900/5 data-[highlighted]:bg-neutral-900/5 aria-selected:bg-neutral-900/9 aria-disabled:cursor-not-allowed aria-disabled:text-neutral-900/40 dark:text-neutral-100 dark:[&_svg]:text-neutral-100/80 dark:hover:bg-neutral-100/5 dark:focus-visible:bg-neutral-100/5 dark:data-[highlighted]:bg-neutral-100/5 dark:aria-selected:bg-neutral-100/9 dark:aria-disabled:text-neutral-100/40";
const overlayEnter =
  "transition-opacity duration-[160ms] ease-[cubic-bezier(0.2,0,0,1)] starting:opacity-0 motion-reduce:transition-none";
const sheetPanel =
  "flex h-full min-h-0 flex-col bg-neutral-50 text-neutral-900 dark:text-neutral-100 outline-none shadow-[0_12px_24px_-12px_rgb(0_0_0/0.2)] dark:shadow-[0_12px_24px_-12px_rgb(0_0_0/0.6)] dark:bg-neutral-900";
const dialogCard =
  "mx-1 mt-1 flex min-h-0 flex-1 flex-col overflow-y-auto rounded-[var(--rb-r-sm,6px)] border border-neutral-900/8 bg-white [&:last-child]:mb-1 dark:border-neutral-100/8 dark:bg-neutral-950";
const navItem =
  "flex h-8 w-full min-w-0 cursor-pointer items-center gap-2 rounded-[var(--rb-r-sm,6px)] px-2 text-left text-[14px]/[16px] font-[450] text-neutral-900/80 transition-[background-color,color] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] [&_svg]:shrink-0 hover:bg-neutral-900/5 hover:text-neutral-900 aria-[current=page]:bg-neutral-900/9 aria-[current=page]:text-neutral-900 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))] dark:text-neutral-100/80 dark:hover:bg-neutral-100/5 dark:hover:text-neutral-100 dark:aria-[current=page]:bg-neutral-100/9 dark:aria-[current=page]:text-neutral-100";
const navCount =
  "ms-auto text-[12px]/[16px] font-[450] tabular-nums text-neutral-900/66 dark:text-neutral-100/66";
const navGroupLabel =
  "px-2 pt-4 pb-1 text-[12px]/[16px] font-normal tabular-nums text-neutral-900/66 dark:text-neutral-100/66";
const fadeTop =
  "bg-linear-to-b from-white to-transparent transition-opacity duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] dark:from-neutral-950";
const fadeBottom =
  "bg-linear-to-t from-white to-transparent transition-opacity duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] dark:from-neutral-950";
const fadeTopOnPaper =
  "bg-linear-to-b from-white to-transparent transition-opacity duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] dark:from-neutral-900";
const fadeBottomOnPaper =
  "bg-linear-to-t from-white to-transparent transition-opacity duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] dark:from-neutral-900";
const userBubble =
  "w-fit max-w-full rounded-[var(--rb-r-lg,10px)] border border-neutral-900/10 dark:border-neutral-100/14 bg-white dark:bg-neutral-900 px-3.5 py-2 text-[14px]/[20px] whitespace-pre-wrap text-neutral-900 dark:text-neutral-100 [overflow-wrap:anywhere]";
const prose =
  "flex min-w-0 flex-col gap-3 text-[14px]/[20px] text-neutral-900 dark:text-neutral-100 [overflow-wrap:anywhere]";
const composer =
  "relative flex w-full min-w-0 flex-col rounded-[var(--rb-r-lg,10px)] border border-neutral-900/12 bg-white text-neutral-900 dark:text-neutral-100 shadow-[0_1px_2px_-1px_rgb(0_0_0/0.12)] transition-[border-color,box-shadow] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] hover:not-focus-within:border-neutral-900/32 focus-within:border-[var(--rb-accent,oklch(20.5%_0_0))] focus-within:ring-3 focus-within:ring-neutral-900/8 dark:border-neutral-100/14 dark:bg-neutral-900 dark:shadow-[0_1px_2px_-1px_rgb(0_0_0/0.36)] dark:hover:not-focus-within:border-neutral-100/32 dark:focus-within:border-[var(--rb-accent,oklch(100%_0_0))] dark:focus-within:ring-neutral-100/8";
const composerInput =
  "max-h-40 min-h-5 min-w-0 flex-auto resize-none bg-transparent px-1.5 py-1 field-sizing-content text-[14px]/[20px] text-neutral-900 dark:text-neutral-100 outline-none placeholder:text-neutral-900/66 dark:placeholder:text-neutral-100/66";

const brandMark =
  "inline-flex shrink-0 items-center justify-center overflow-hidden rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] text-[var(--rb-accent-fg,oklch(100%_0_0))] select-none dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]";

type Model = {
  id: string;
  name: string;
  note: string;
  icon: IconSvgElement;
};

const MODELS: Model[] = [
  {
    id: "atlas",
    name: "Atlas 4",
    note: "Best for hard reasoning",
    icon: SparklesIcon,
  },
  {
    id: "wren",
    name: "Wren",
    note: "Fast, good for drafts",
    icon: Globe02Icon,
  },
  { id: "orbit", name: "Orbit", note: "Long documents", icon: DatabaseIcon },
];

type Prompt = { label: string; icon: IconSvgElement };

const PROMPTS: Prompt[] = [
  { label: "Draft an email", icon: Mail01Icon },
  { label: "Summarise a file", icon: File02Icon },
  { label: "Write a function", icon: SourceCodeIcon },
  { label: "Plan a trip", icon: Location01Icon },
];

type Turn = { role: "user" | "assistant"; text: string };

type Chat = {
  id: string;
  title: string;
  icon: IconSvgElement;
  model: string;
  count: number;
  turns: Turn[];
};

const CHATS: Chat[] = [
  {
    id: "revenue",
    title: "Quarterly revenue summary",
    icon: File02Icon,
    model: "Atlas 4",
    count: 12,
    turns: [
      {
        role: "user",
        text: "Pull the headline numbers out of the Q3 close and tell me what actually moved.",
      },
      {
        role: "assistant",
        text: "Revenue landed at 4.62m, up 8.4% on Q2. Almost all of the gain is expansion inside existing accounts rather than new logos, which came in flat at 41. Net retention is 114%, the best of the last four quarters.",
      },
      {
        role: "user",
        text: "What is the risk in that number?",
      },
      {
        role: "assistant",
        text: "Concentration. The top six accounts now sit at 38% of ARR, up from 31% a year ago. Two of them renew in the same month, so a single bad renewal window would take more off the top line than the whole quarter's growth put back.",
      },
    ],
  },
  {
    id: "auth",
    title: "Refactor auth middleware",
    icon: SourceCodeIcon,
    model: "Atlas 4",
    count: 24,
    turns: [
      {
        role: "user",
        text: "The session refresh runs on every request. Can it be cheaper?",
      },
      {
        role: "assistant",
        text: "Yes. Refresh only when the token is inside its last five minutes, and keep the decoded claims on the request so downstream handlers stop re-parsing. That takes the common path from one network call to none.",
      },
    ],
  },
  {
    id: "lisbon",
    title: "Lisbon trip itinerary",
    icon: Location01Icon,
    model: "Wren",
    count: 8,
    turns: [
      { role: "user", text: "Four days in Lisbon in October, no rental car." },
      {
        role: "assistant",
        text: "Base yourself in Príncipe Real so most of it is walkable. Day one Alfama and the viewpoints, day two Belém by tram, day three a train out to Sintra, day four the Time Out market and the riverside before your flight.",
      },
    ],
  },
  {
    id: "launch",
    title: "Launch checklist draft",
    icon: CheckListIcon,
    model: "Atlas 4",
    count: 5,
    turns: [
      { role: "user", text: "What is still open before Thursday?" },
      {
        role: "assistant",
        text: "Three things: the pricing page copy is unreviewed, the status page has no incident template, and nobody owns the first-hour inbox. The rest is either done or does not block the announcement.",
      },
    ],
  },
  {
    id: "vector",
    title: "Explain vector search",
    icon: Search01Icon,
    model: "Orbit",
    count: 9,
    turns: [
      {
        role: "user",
        text: "Explain vector search without the marketing words.",
      },
      {
        role: "assistant",
        text: "You turn each document into a list of numbers that encodes its meaning, then store them. A query becomes the same kind of list, and you return the documents whose lists point in a similar direction. It finds things that mean the same rather than things that spell the same.",
      },
    ],
  },
  {
    id: "sql",
    title: "Optimise a slow SQL query",
    icon: DatabaseIcon,
    model: "Orbit",
    count: 16,
    turns: [
      {
        role: "user",
        text: "This join takes eleven seconds. Where do I start?",
      },
      {
        role: "assistant",
        text: "Read the plan first. An eleven second join on this row count is almost always a sequential scan on the right-hand table because the join column is not indexed, or is indexed with a different collation than the one you are comparing against.",
      },
    ],
  },
];

const QUICK_ACTIONS: { label: string; icon: IconSvgElement; count?: number }[] =
  [
    { label: "Favourites", icon: StarIcon, count: 2 },
    { label: "Templates", icon: LayoutTemplateIcon, count: 5 },
    { label: "History", icon: HistoryIcon },
    { label: "Clear history", icon: Delete02Icon },
  ];

function Overline({ children }: { children: React.ReactNode }) {
  return <p className={navGroupLabel}>{children}</p>;
}

function ModelMenu({
  value,
  onChange,
}: {
  value: Model;
  onChange: (model: Model) => void;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([]);

  const close = useCallback((restore: boolean) => {
    setOpen(false);
    if (restore) triggerRef.current?.focus({ preventScroll: true });
  }, []);

  useEffect(() => {
    if (!open) return;
    const doc = triggerRef.current?.ownerDocument ?? document;

    const onPointerDown = (event: Event) => {
      const target = event.target as Node;
      if (menuRef.current?.contains(target)) return;
      if (triggerRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        close(true);
      }
    };

    doc.addEventListener("pointerdown", onPointerDown);
    doc.addEventListener("keydown", onKeyDown);
    const frame = requestAnimationFrame(() =>
      itemRefs.current[active]?.focus({ preventScroll: true }),
    );
    return () => {
      cancelAnimationFrame(frame);
      doc.removeEventListener("pointerdown", onPointerDown);
      doc.removeEventListener("keydown", onKeyDown);
    };
  }, [open, active, close]);

  const move = (next: number) => {
    const wrapped = (next + MODELS.length) % MODELS.length;
    setActive(wrapped);
    itemRefs.current[wrapped]?.focus({ preventScroll: true });
  };

  const onItemKeyDown = (
    event: KeyboardEvent<HTMLButtonElement>,
    i: number,
  ) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      move(i + 1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      move(i - 1);
    } else if (event.key === "Home") {
      event.preventDefault();
      move(0);
    } else if (event.key === "End") {
      event.preventDefault();
      move(MODELS.length - 1);
    } else if (event.key === "Tab") {
      setOpen(false);
    }
  };

  return (
    <div className="relative">
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => {
          setActive(MODELS.findIndex((m) => m.id === value.id));
          setOpen((v) => !v);
        }}
        className={cx(secondaryButton, buttonMd)}
      >
        <HugeiconsIcon
          icon={value.icon}
          size={16}
          strokeWidth={1.8}
          aria-hidden="true"
        />
        {value.name}
        <HugeiconsIcon
          icon={ArrowDown01Icon}
          size={14}
          strokeWidth={1.8}
          aria-hidden="true"
          className={cx(selectCaret, open && "rotate-180")}
        />
      </button>

      {open && (
        <div
          ref={menuRef}
          role="menu"
          aria-label="Choose a model"
          className={cx(
            menuPopup,
            "absolute left-0 top-[calc(100%+6px)] z-50 w-64",
          )}
        >
          {MODELS.map((model, i) => {
            const selected = model.id === value.id;
            return (
              <button
                key={model.id}
                ref={(node) => {
                  itemRefs.current[i] = node;
                }}
                type="button"
                role="menuitemradio"
                aria-checked={selected}
                tabIndex={i === active ? 0 : -1}
                onKeyDown={(event) => onItemKeyDown(event, i)}
                onClick={() => {
                  onChange(model);
                  close(true);
                }}
                className={menuItem}
              >
                <HugeiconsIcon
                  icon={model.icon}
                  size={16}
                  strokeWidth={1.8}
                  aria-hidden="true"
                />
                <span className="min-w-0 flex-1">
                  <span className="block truncate">{model.name}</span>
                  <span className={cx(typeBodySm, text2, "block truncate")}>
                    {model.note}
                  </span>
                </span>
                {selected && (
                  <HugeiconsIcon
                    icon={Tick02Icon}
                    size={16}
                    strokeWidth={1.8}
                    aria-hidden="true"
                  />
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

function Composer({
  draft,
  onDraft,
  model,
  inputRef,
}: {
  draft: string;
  onDraft: (value: string) => void;
  model: Model;
  inputRef: React.RefObject<HTMLTextAreaElement | null>;
}) {
  return (
    <div className={composer}>
      <label htmlFor="halden-composer" className="sr-only">
        Message Halden
      </label>
      <div className="px-2 pt-2">
        <textarea
          id="halden-composer"
          ref={inputRef as React.RefObject<HTMLTextAreaElement>}
          rows={1}
          value={draft}
          onChange={(event) => {
            onDraft(event.target.value);
            const el = event.target;
            el.style.height = "auto";
            el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
          }}
          placeholder="Message Halden"
          className={cx(composerInput, "block w-full")}
        />
      </div>
      <div className="flex items-center gap-1 px-2 pb-2 pt-1">
        <button
          type="button"
          aria-label="Add an attachment"
          className={cx(ghostButton, iconButtonSm)}
        >
          <HugeiconsIcon
            icon={Add01Icon}
            size={16}
            strokeWidth={1.8}
            aria-hidden="true"
          />
        </button>
        <button
          type="button"
          aria-pressed="false"
          className={cx(ghostButton, buttonSm)}
        >
          <HugeiconsIcon
            icon={Globe02Icon}
            size={16}
            strokeWidth={1.8}
            aria-hidden="true"
          />
          Search
        </button>

        <div className="ml-auto flex items-center gap-1">
          <span
            className={cx(typeCaption, text3, "hidden shrink-0 pr-1 sm:block")}
          >
            {model.name}
          </span>
          <button
            type="button"
            aria-label="Dictate"
            className={cx(ghostButton, iconButtonSm)}
          >
            <HugeiconsIcon
              icon={Mic01Icon}
              size={16}
              strokeWidth={1.8}
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            aria-label="Send message"
            disabled={draft.trim().length === 0}
            className={cx(primaryButton, iconButtonSm)}
          >
            <HugeiconsIcon
              icon={ArrowUp02Icon}
              size={16}
              strokeWidth={1.8}
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
    </div>
  );
}

function SidebarBody({
  model,
  onModel,
  chatId,
  onChat,
  onNew,
  onClose,
  onCollapse,
  collapseRef,
}: {
  model: Model;
  onModel: (model: Model) => void;
  chatId: string | null;
  onChat: (id: string) => void;
  onNew: () => void;
  onClose?: () => void;
  onCollapse?: () => void;
  collapseRef?: React.Ref<HTMLButtonElement>;
}) {
  const pinned = CHATS[0];
  const {
    ref: navRef,
    edges: navEdges,
    onScroll: onNavScroll,
  } = useScrollFade<HTMLDivElement>();

  return (
    <>
      <div className="flex h-[60px] shrink-0 items-center gap-2.5 px-3">
        <span aria-hidden="true" className={cx(brandMark, "size-8", typeLabel)}>
          H
        </span>
        <p className={cx(typeHeadingXs, "min-w-0 flex-1 truncate")}>Halden</p>
        {onClose ? (
          <button
            type="button"
            aria-label="Close navigation"
            onClick={onClose}
            className={cx(ghostButton, iconButtonMd)}
          >
            <HugeiconsIcon
              icon={Cancel01Icon}
              size={16}
              strokeWidth={1.8}
              aria-hidden="true"
            />
          </button>
        ) : (
          <button
            ref={collapseRef}
            type="button"
            aria-label="Collapse sidebar"
            aria-expanded={true}
            onClick={onCollapse}
            className={cx(ghostButton, iconButtonMd, "max-lg:hidden")}
          >
            <HugeiconsIcon
              icon={SidebarLeftIcon}
              size={16}
              strokeWidth={1.8}
              aria-hidden="true"
            />
          </button>
        )}
      </div>

      <div className="shrink-0 px-3 pb-3">
        <button
          type="button"
          onClick={onNew}
          className={cx(primaryButton, buttonMd, "w-full")}
        >
          <HugeiconsIcon
            icon={Add01Icon}
            size={16}
            strokeWidth={1.8}
            aria-hidden="true"
          />
          <span className="min-w-0 flex-1 truncate text-left">New chat</span>
        </button>
      </div>

      <div className="relative min-h-0 flex-1">
        <div
          ref={navRef}
          onScroll={onNavScroll}
          className="h-full overflow-y-auto px-3 pb-3"
        >
          <div>
            <Overline>Model</Overline>
            <div
              role="radiogroup"
              aria-label="Model"
              className="grid grid-cols-3 gap-1.5"
            >
              {MODELS.map((option) => {
                const selected = option.id === model.id;
                return (
                  <button
                    key={option.id}
                    type="button"
                    role="radio"
                    aria-checked={selected}
                    onClick={() => onModel(option)}
                    className={cx(
                      transition,
                      focusRing,
                      cornerSm,
                      selected
                        ? cx(paper, thumbLift, markEdge, text1)
                        : cx(hoverFill, controlEdge, text2),
                      "flex min-w-0 cursor-pointer flex-col items-center justify-center gap-1.5 px-2 py-2.5 select-none",
                    )}
                  >
                    <HugeiconsIcon
                      icon={option.icon}
                      size={16}
                      strokeWidth={1.8}
                      aria-hidden="true"
                    />
                    <span
                      className={cx(typeLabelSm, "w-full truncate text-center")}
                    >
                      {option.name}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <Overline>Pinned</Overline>
            <button
              type="button"
              onClick={() => onChat(pinned.id)}
              className={cx(
                frame,
                itemRowInteractive,
                focusRing,
                transition,
                "w-full px-2.5 py-2 text-left",
              )}
            >
              <span className="flex items-center gap-2">
                <HugeiconsIcon
                  icon={pinned.icon}
                  size={16}
                  strokeWidth={1.8}
                  aria-hidden="true"
                  className={text3}
                />
                <span className={cx(typeLabel, "min-w-0 flex-1 truncate")}>
                  {pinned.title}
                </span>
              </span>
              <span className="mt-1.5 flex items-center gap-1.5 pl-6">
                <span className={cx(badge, badgeNeutral)}>{pinned.model}</span>
                <span className={cx(typeCaption, text3)}>
                  {pinned.count} messages
                </span>
              </span>
            </button>
          </div>

          <div>
            <Overline>Recent</Overline>
            <ul className="space-y-0.5">
              {CHATS.map((chat) => {
                const selected = chat.id === chatId;
                return (
                  <li key={chat.id}>
                    <button
                      type="button"
                      aria-current={selected ? "page" : undefined}
                      onClick={() => onChat(chat.id)}
                      className={navItem}
                    >
                      <HugeiconsIcon
                        icon={chat.icon}
                        size={16}
                        strokeWidth={1.8}
                        aria-hidden="true"
                      />
                      <span className="min-w-0 flex-1 truncate text-left">
                        {chat.title}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>

          <div>
            <Overline>Quick actions</Overline>
            <ul className="space-y-0.5">
              {QUICK_ACTIONS.map((action) => (
                <li key={action.label}>
                  <button type="button" className={navItem}>
                    <HugeiconsIcon
                      icon={action.icon}
                      size={16}
                      strokeWidth={1.8}
                      aria-hidden="true"
                    />
                    <span className="min-w-0 flex-1 truncate text-left">
                      {action.label}
                    </span>
                    {action.count !== undefined && (
                      <span className={navCount}>{action.count}</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
        <div
          aria-hidden="true"
          className={cx(
            onClose ? fadeTop : fadeTopOnPaper,
            "pointer-events-none absolute inset-x-0 top-0 h-8",
            navEdges.start ? "opacity-100" : "opacity-0",
          )}
        />
        <div
          aria-hidden="true"
          className={cx(
            onClose ? fadeBottom : fadeBottomOnPaper,
            "pointer-events-none absolute inset-x-0 bottom-0 h-8",
            navEdges.end ? "opacity-100" : "opacity-0",
          )}
        />
      </div>

      <div className="flex h-[60px] shrink-0 items-center gap-2.5 px-3">
        <span className="relative shrink-0">
          <span aria-hidden="true" className={avatar}>
            <span className={avatarFallback}>HA</span>
          </span>
        </span>
        <span className="min-w-0 flex-1">
          <span className={cx(typeLabel, "block truncate")}>Assistant</span>
          <span className={cx(typeBodySm, text2, "block truncate")}>
            Online, replies instantly
          </span>
        </span>
      </div>
    </>
  );
}

export default function AppShell9({
  sidebar,
  children,
  sidebarOpen: sidebarOpenProp,
  onSidebarOpenChange,
  sidebarWidth = "w-72",
}: {
  sidebar?: ReactNode;
  children?: ReactNode;
  sidebarOpen?: boolean;
  onSidebarOpenChange?: (open: boolean) => void;
  sidebarWidth?: string;
} = {}) {
  const [model, setModel] = useState(MODELS[0]);
  const [chatId, setChatId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [sidebarOpenState, setSidebarOpenState] = useState(true);
  const sidebarOpen = sidebarOpenProp ?? sidebarOpenState;
  const setSidebarOpen = useCallback(
    (next: boolean) => {
      if (onSidebarOpenChange) {
        onSidebarOpenChange(next);
      } else {
        setSidebarOpenState(next);
      }
    },
    [onSidebarOpenChange],
  );
  const [navOpen, setNavOpen] = useState(false);
  const [navShown, setNavShown] = useState(false);

  const rootRef = useRef<HTMLDivElement>(null);
  const menuTriggerRef = useRef<HTMLButtonElement>(null);
  const expandRef = useRef<HTMLButtonElement>(null);
  const collapseRef = useRef<HTMLButtonElement>(null);
  const railFocusRef = useRef(false);
  const drawerRef = useRef<HTMLElement>(null);
  const threadRef = useRef<HTMLDivElement>(null);
  const {
    ref: threadScrollRef,
    edges: threadEdges,
    onScroll: onThreadScroll,
  } = useScrollFade<HTMLDivElement>();
  const {
    ref: emptyRef,
    edges: emptyEdges,
    onScroll: onEmptyScroll,
  } = useScrollFade<HTMLDivElement>();
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const shouldFocusRef = useRef(false);

  useEffect(() => {
    if (!railFocusRef.current) return;
    railFocusRef.current = false;
    const target = sidebarOpen ? collapseRef.current : expandRef.current;
    target?.focus({ preventScroll: true });
  }, [sidebarOpen]);

  const chat = useMemo(
    () => CHATS.find((c) => c.id === chatId) ?? null,
    [chatId],
  );

  const closeNav = useCallback(() => {
    setNavShown(false);
    setNavOpen(false);
    if (shouldFocusRef.current) {
      menuTriggerRef.current?.focus({ preventScroll: true });
      shouldFocusRef.current = false;
    }
  }, []);

  useEffect(() => {
    if (!navOpen) return;
    const drawer = drawerRef.current;
    const doc = drawer?.ownerDocument ?? document;
    const frame = requestAnimationFrame(() => setNavShown(true));

    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        closeNav();
        return;
      }
      if (event.key !== "Tab" || !drawer) return;
      const nodes = drawer.querySelectorAll<HTMLElement>(
        'a[href],button:not([disabled]),input,textarea,select,[tabindex]:not([tabindex="-1"])',
      );
      if (nodes.length === 0) return;
      const first = nodes[0];
      const last = nodes[nodes.length - 1];
      if (event.shiftKey && doc.activeElement === first) {
        event.preventDefault();
        last.focus({ preventScroll: true });
      } else if (!event.shiftKey && doc.activeElement === last) {
        event.preventDefault();
        first.focus({ preventScroll: true });
      }
    };

    doc.addEventListener("keydown", onKeyDown);
    return () => {
      cancelAnimationFrame(frame);
      doc.removeEventListener("keydown", onKeyDown);
    };
  }, [navOpen, closeNav]);

  useEffect(() => {
    if (threadRef.current) threadRef.current.scrollTop = 0;
  }, [chatId]);

  const openChat = (id: string) => {
    setChatId(id);
    closeNav();
  };

  const startNew = () => {
    setChatId(null);
    setDraft("");
    closeNav();
    composerRef.current?.focus({ preventScroll: true });
  };

  return (
    <div
      ref={rootRef}
      className={cx(
        canvas,
        text1,
        "relative flex h-full min-h-0 w-full overflow-hidden",
      )}
    >
      <div className={cx(canvas, "grid h-full min-h-0 w-full min-w-0 grid-cols-[auto_minmax(0,1fr)]")}>
        <div className={cx(frame, "shell-main", "col-start-2 row-start-1 flex min-h-0 min-w-0 flex-col")}>
          {children ?? (
          <>
          <header className="flex h-[60px] shrink-0 items-center px-3">
            <button
              ref={menuTriggerRef}
              type="button"
              aria-label="Open navigation"
              aria-expanded={navOpen}
              onClick={() => {
                shouldFocusRef.current = true;
                setNavOpen(true);
              }}
              className={cx(ghostButton, iconButtonMd, "mr-2 lg:hidden")}
            >
              <HugeiconsIcon
                icon={Menu01Icon}
                size={16}
                strokeWidth={1.8}
                aria-hidden="true"
              />
            </button>

            <div
              className={cx(
                "hidden shrink-0 overflow-hidden lg:block",
                sidebarOpen ? "mr-0 w-0" : "mr-2 w-9",
              )}
            >
              <button
                ref={expandRef}
                type="button"
                aria-label="Expand sidebar"
                aria-expanded={sidebarOpen}
                tabIndex={sidebarOpen ? -1 : undefined}
                onClick={() => {
                  railFocusRef.current = true;
                  setSidebarOpen(true);
                }}
                className={cx(ghostButton, iconButtonMd)}
              >
                <HugeiconsIcon
                  icon={SidebarLeftIcon}
                  size={16}
                  strokeWidth={1.8}
                  aria-hidden="true"
                />
              </button>
            </div>

            <ModelMenu value={model} onChange={setModel} />

            <h1
              className={cx(
                typeBodySm,
                text3,
                "ml-2 hidden min-w-0 flex-1 truncate sm:block",
              )}
            >
              {chat ? chat.title : "New chat"}
            </h1>
            <span className="flex-1 sm:hidden" />

            <button
              type="button"
              className={cx(secondaryButton, buttonMd, "ml-2")}
            >
              <HugeiconsIcon
                icon={Share08Icon}
                size={16}
                strokeWidth={1.8}
                aria-hidden="true"
              />
              <span className="hidden sm:inline">Share chat</span>
            </button>
          </header>

          {chat ? (
            <>
              <div className="relative flex min-h-0 flex-1 flex-col">
                <div
                  ref={(el) => {
                    Object.assign(threadRef, { current: el });
                    Object.assign(threadScrollRef, { current: el });
                  }}
                  onScroll={onThreadScroll}
                  className="h-full overflow-y-auto px-4 sm:px-6"
                >
                  <div className="mx-auto flex max-w-2xl flex-col gap-5 py-6">
                    {chat.turns.map((turn, i) =>
                      turn.role === "user" ? (
                        <div key={i} className="flex justify-end">
                          <div className="max-w-[85%]">
                            <p className={userBubble}>{turn.text}</p>
                          </div>
                        </div>
                      ) : (
                        <p key={i} className={cx(prose, "max-w-[95%]")}>
                          {turn.text}
                        </p>
                      ),
                    )}
                  </div>
                </div>
                <div
                  aria-hidden="true"
                  className={cx(
                    fadeTopOnPaper,
                    "pointer-events-none absolute inset-x-0 top-0 h-8",
                    threadEdges.start ? "opacity-100" : "opacity-0",
                  )}
                />
                <div
                  aria-hidden="true"
                  className={cx(
                    fadeBottomOnPaper,
                    "pointer-events-none absolute inset-x-0 bottom-0 h-8",
                    threadEdges.end ? "opacity-100" : "opacity-0",
                  )}
                />
              </div>

              <div className="shrink-0 px-4 pt-2 sm:px-6">
                <div className="mx-auto max-w-2xl">
                  <Composer
                    draft={draft}
                    onDraft={setDraft}
                    model={model}
                    inputRef={composerRef}
                  />
                </div>
              </div>
            </>
          ) : (
            <div className="relative flex min-h-0 flex-1 flex-col">
              <div
                className="flex h-full flex-col items-center justify-center-safe overflow-y-auto px-4 py-6 sm:px-6"
                ref={emptyRef}
                onScroll={onEmptyScroll}
              >
                <span
                  aria-hidden="true"
                  className={cx(brandMark, "size-11", typeHeadingXs)}
                >
                  H
                </span>
                <h2 className={cx(typeHeading, "mt-4")}>How can I help?</h2>
                <p className={cx(typeBody, text2, "mt-1.5")}>
                  Start typing, or pick one of these to get going.
                </p>

                <div className="mt-6 w-full max-w-2xl">
                  <Composer
                    draft={draft}
                    onDraft={setDraft}
                    model={model}
                    inputRef={composerRef}
                  />
                </div>

                <div className="mt-3 grid w-full max-w-2xl grid-cols-1 gap-2 sm:grid-cols-2">
                  {PROMPTS.map((prompt) => (
                    <button
                      key={prompt.label}
                      type="button"
                      onClick={() => {
                        setDraft(prompt.label);
                        composerRef.current?.focus({ preventScroll: true });
                      }}
                      className={cx(secondaryButton, buttonLg)}
                    >
                      <HugeiconsIcon
                        icon={prompt.icon}
                        size={16}
                        strokeWidth={1.8}
                        aria-hidden="true"
                        className={text3}
                      />
                      <span className="min-w-0 flex-1 truncate text-left">
                        {prompt.label}
                      </span>
                      <HugeiconsIcon
                        icon={ArrowUpRight01Icon}
                        size={16}
                        strokeWidth={1.8}
                        aria-hidden="true"
                        className={text3}
                      />
                    </button>
                  ))}
                </div>
              </div>
              <div
                aria-hidden="true"
                className={cx(
                  fadeTopOnPaper,
                  "pointer-events-none absolute inset-x-0 top-0 h-8",
                  emptyEdges.start ? "opacity-100" : "opacity-0",
                )}
              />
              <div
                aria-hidden="true"
                className={cx(
                  fadeBottomOnPaper,
                  "pointer-events-none absolute inset-x-0 bottom-0 h-8",
                  emptyEdges.end ? "opacity-100" : "opacity-0",
                )}
              />
            </div>
          )}

          <p
            className={cx(
              typeBodySm,
              text2,
              "shrink-0 px-4 pb-3 pt-2 text-center sm:px-6",
            )}
          >
            Halden can make mistakes. Check anything important.
          </p>
          </>
          )}
        </div>
        <aside
          {...(!sidebarOpen ? ({ inert: "" } as Record<string, unknown>) : {})}
          className={cx(
            "col-start-1 row-start-1 h-full min-h-0",
            sidebarOpen ? sidebarWidth : "w-0 overflow-hidden",
          )}
        >
          <div className={cx(frame, "shell-rail", "h-full", sidebarWidth)}>
            {sidebar ?? (
              <SidebarBody
                model={model}
                onModel={setModel}
                chatId={chatId}
                onChat={openChat}
                onNew={startNew}
                collapseRef={collapseRef}
                onCollapse={() => {
                  railFocusRef.current = true;
                  setSidebarOpen(false);
                }}
              />
            )}
          </div>
        </aside>
      </div>

      {navOpen && (
        <div className="lg:hidden">
          <button
            type="button"
            aria-label="Close navigation overlay"
            onClick={closeNav}
            tabIndex={-1}
            className={cx(
              backdrop,
              overlayEnter,
              "absolute inset-0 z-30 cursor-pointer",
              navShown ? "opacity-100" : "opacity-0",
            )}
          />
          <aside
            ref={drawerRef}
            role="dialog"
            aria-modal="true"
            aria-label="Navigation"
            className={cx(
              sheetPanel,
              overlayEnter,
              "absolute inset-y-0 left-0 z-40 w-72 max-w-[85%]",
              navShown ? "opacity-100" : "opacity-0",
            )}
          >
            <div className={dialogCard}>
              <SidebarBody
                model={model}
                onModel={setModel}
                chatId={chatId}
                onChat={openChat}
                onNew={startNew}
                onClose={closeNav}
              />
            </div>
          </aside>
        </div>
      )}
    </div>
  );
}
