"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type RefObject,
} from "react";
import { motion, useReducedMotion } from "motion/react";
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react";
import {
  ArrowDown02Icon,
  ArrowUp02Icon,
  CheckListIcon,
  FileSearchIcon,
  GitPullRequestIcon,
} from "@hugeicons/core-free-icons";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

const canvas = "bg-white dark:bg-neutral-950";
const text1 = "text-neutral-900 dark:text-neutral-100";
const text2 = "text-neutral-900/80 dark:text-neutral-100/80";
const text3 = "text-neutral-900/66 dark:text-neutral-100/66";
const typeBodySm = "text-[13px]/[18px] font-normal";
const focusRingInset =
  "focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";
const primaryButton =
  "relative inline-flex shrink-0 cursor-pointer items-center justify-center whitespace-nowrap select-none transition-[color,background-color,border-color,box-shadow,outline-color,scale,filter] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] active:scale-[0.98] active:duration-[60ms] motion-reduce:active:scale-100 disabled:cursor-not-allowed aria-busy:cursor-progress focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))] bg-[linear-gradient(180deg,color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_84%,white),color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_94%,black))] text-[var(--rb-accent-fg,oklch(100%_0_0))] shadow-[inset_0_2px_0_-1px_rgb(255_255_255/0.2),inset_0_-2px_0_-1px_rgb(0_0_0/0.3),inset_0_0_0_1px_color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_88%,black),0_1px_1px_rgb(0_0_0/0.22),0_2px_5px_-2px_rgb(0_0_0/0.3)] hover:not-disabled:brightness-110 active:shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_88%,black),inset_0_1px_2px_rgb(0_0_0/0.42)] disabled:opacity-40 disabled:shadow-none dark:bg-[linear-gradient(180deg,color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_84%,white),color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_94%,black))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))] dark:shadow-[inset_0_2px_0_-1px_rgb(255_255_255/0.2),inset_0_-2px_0_-1px_rgb(0_0_0/0.3),inset_0_0_0_1px_color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_88%,black),0_1px_1px_rgb(0_0_0/0.66),0_2px_5px_-2px_rgb(0_0_0/0.9)] dark:active:shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_88%,black),inset_0_1px_2px_rgb(0_0_0/0.42)] dark:disabled:shadow-none";
const secondaryButton =
  "relative inline-flex shrink-0 cursor-pointer items-center justify-center whitespace-nowrap select-none transition-[color,background-color,border-color,box-shadow,outline-color,scale,filter] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] active:scale-[0.98] active:duration-[60ms] motion-reduce:active:scale-100 disabled:cursor-not-allowed aria-busy:cursor-progress focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))] border border-neutral-900/12 bg-white text-neutral-900 shadow-[0_1px_2px_-1px_rgb(0_0_0/0.12)] hover:not-disabled:border-neutral-900/32 hover:not-disabled:bg-gradient-to-b hover:not-disabled:from-neutral-900/3 hover:not-disabled:to-neutral-900/3 active:shadow-[inset_0_1px_2px_rgb(0_0_0/0.12)] disabled:border-neutral-900/8 disabled:text-neutral-900/40 disabled:shadow-none dark:border-neutral-100/14 dark:bg-neutral-900 dark:text-neutral-100 dark:shadow-[0_1px_2px_-1px_rgb(0_0_0/0.36)] dark:hover:not-disabled:border-neutral-100/32 dark:hover:not-disabled:from-neutral-100/3 dark:hover:not-disabled:to-neutral-100/3 dark:active:shadow-[inset_0_1px_2px_rgb(0_0_0/0.4)] dark:disabled:border-neutral-100/8 dark:disabled:text-neutral-100/40";
const buttonMd =
  "h-8 gap-2 rounded-[var(--rb-r-sm,6px)] px-4 text-[14px]/[16px] font-[450]";
const iconButtonSm = "size-7 rounded-[var(--rb-r-sm,6px)]";
const statusWord =
  "inline-flex items-start gap-1.5 text-[14px]/[16px] font-[450] tabular-nums";
const statusIdle = "text-neutral-900/66 dark:text-neutral-100/66";
const arcLine = "flex h-5 shrink-0 items-center";
const runningArc =
  "inline-block size-2.5 shrink-0 rounded-full border border-current border-t-transparent animate-spin motion-reduce:animate-none";
const panel =
  "relative flex min-w-0 flex-col overflow-hidden rounded-[var(--rb-r-lg,10px)] border border-neutral-900/10 dark:border-neutral-100/14 bg-neutral-50 text-neutral-900 dark:text-neutral-100 dark:bg-neutral-900";
const panelHead =
  "flex min-h-10 min-w-0 shrink-0 items-center gap-2 px-4 has-[button]:pe-2";
const panelBody =
  "mx-1 mb-1 flex min-w-0 flex-col overflow-hidden rounded-[var(--rb-r-sm,6px)] border border-neutral-900/8 bg-white [&:has(+*)]:mb-0 dark:border-neutral-100/8 dark:bg-neutral-950";
const frameTitle =
  "min-w-0 truncate text-[14px]/[16px] font-[450] text-neutral-900 dark:text-neutral-100";
const rowsRuled = "divide-y divide-neutral-900/8 dark:divide-neutral-100/8";
const itemRowSm =
  "relative flex min-h-8 w-full min-w-0 flex-wrap items-center gap-x-3 gap-y-1 py-1.5 text-left text-neutral-900 dark:text-neutral-100 transition-[background-color] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)]";
const itemRowInteractive =
  "cursor-pointer hover:bg-neutral-900/5 dark:hover:bg-neutral-100/5 active:bg-neutral-900/8 dark:active:bg-neutral-100/8";
const cornerSm = "rounded-[var(--rb-r-sm,6px)]";
const emptyTitle =
  "text-[16px]/[20px] font-[450] tracking-[-0.015em] text-neutral-900 dark:text-neutral-100";
const emptyDescription =
  "max-w-[48ch] text-[13px]/[18px] text-neutral-900/80 dark:text-neutral-100/80";
const userBubble =
  "w-fit max-w-full rounded-[var(--rb-r-lg,10px)] border border-neutral-900/10 dark:border-neutral-100/14 bg-white dark:bg-neutral-900 px-3.5 py-2 text-[14px]/[20px] whitespace-pre-wrap text-neutral-900 dark:text-neutral-100 [overflow-wrap:anywhere]";
const prose =
  "flex min-w-0 flex-col gap-3 text-[14px]/[20px] text-neutral-900 dark:text-neutral-100 [overflow-wrap:anywhere]";
const activityLine =
  "relative flex w-fit max-w-full min-w-0 items-center gap-2 text-[14px]/[16px] font-[450] text-neutral-900 dark:text-neutral-100";
const composer =
  "relative flex w-full min-w-0 flex-col rounded-[var(--rb-r-lg,10px)] border border-neutral-900/12 bg-white text-neutral-900 dark:text-neutral-100 shadow-[0_1px_2px_-1px_rgb(0_0_0/0.12)] transition-[border-color,box-shadow] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] hover:not-focus-within:border-neutral-900/32 focus-within:border-[var(--rb-accent,oklch(20.5%_0_0))] focus-within:ring-2 focus-within:ring-neutral-900/8 dark:border-neutral-100/14 dark:bg-neutral-900 dark:shadow-[0_1px_2px_-1px_rgb(0_0_0/0.36)] dark:hover:not-focus-within:border-neutral-100/32 dark:focus-within:border-[var(--rb-accent,oklch(100%_0_0))] dark:focus-within:ring-neutral-100/8";
const composerRow = "flex min-w-0 items-end gap-1 p-2";
const composerInput =
  "max-h-40 min-h-5 min-w-0 flex-auto resize-none bg-transparent px-1.5 py-1 field-sizing-content text-[14px]/[20px] text-neutral-900 dark:text-neutral-100 outline-none placeholder:text-neutral-900/66 dark:placeholder:text-neutral-100/66";
const fadeTop =
  "bg-gradient-to-b from-white to-transparent transition-opacity duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] dark:from-neutral-950";
const fadeBottom =
  "bg-gradient-to-t from-white to-transparent transition-opacity duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] dark:from-neutral-950";

type Message = {
  id: string;
  role: "user" | "assistant";
  text: string;
};

const CATCH_UP_MS = 180;
const FLOOR_CPS = 45;

function lastWordBoundary(source: string, cut: number) {
  if (cut >= source.length) return source.length;
  const i = source.lastIndexOf(" ", cut);
  return i === -1 ? 0 : i;
}

function useSmoothedText() {
  const [text, setText] = useState("");
  const [done, setDone] = useState(false);
  const targetRef = useRef("");
  const shownRef = useRef(0);
  const endedRef = useRef(false);
  const reduceMotion = useReducedMotion();

  useEffect(() => {
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const dt = Math.min(now - last, 64);
      last = now;
      const target = targetRef.current;
      const behind = target.length - shownRef.current;
      if (behind > 0) {
        shownRef.current = reduceMotion
          ? target.length
          : Math.min(
              target.length,
              shownRef.current +
                behind * (1 - Math.exp(-dt / CATCH_UP_MS)) +
                (FLOOR_CPS * dt) / 1000,
            );
        const cut = Math.floor(shownRef.current);
        const finished = endedRef.current && cut >= target.length;
        const safe = finished ? target.length : lastWordBoundary(target, cut);
        setText(target.slice(0, safe));
        if (finished) setDone(true);
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [reduceMotion]);

  const push = useCallback((chunk: string) => {
    targetRef.current += chunk;
  }, []);
  const end = useCallback(() => {
    endedRef.current = true;
  }, []);
  const reset = useCallback(() => {
    targetRef.current = "";
    shownRef.current = 0;
    endedRef.current = false;
    setText("");
    setDone(false);
  }, []);
  return { text, done, push, end, reset };
}

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

function useStickToBottom(scrollRef: RefObject<HTMLDivElement | null>) {
  const stickRef = useRef(true);
  const [showJump, setShowJump] = useState(false);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;

    const sync = () => {
      const overflowing = el.scrollHeight - el.clientHeight > 1;
      const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
      const atBottom = !overflowing || distance < 24;
      stickRef.current = atBottom;
      setShowJump(overflowing && !atBottom);
    };

    const onIntent = (e: WheelEvent | TouchEvent) => {
      const up = "deltaY" in e ? e.deltaY < 0 : true;
      if (!up) return;
      if (el.scrollHeight - el.clientHeight > 1 && el.scrollTop > 0) {
        stickRef.current = false;
      }
    };

    const observer = new ResizeObserver(() => {
      if (stickRef.current) el.scrollTop = el.scrollHeight;
      sync();
    });
    observer.observe(el);
    if (el.firstElementChild) observer.observe(el.firstElementChild);

    el.addEventListener("wheel", onIntent, { passive: true });
    el.addEventListener("touchmove", onIntent, { passive: true });
    el.addEventListener("scroll", sync, { passive: true });

    el.scrollTop = el.scrollHeight;
    sync();
    return () => {
      observer.disconnect();
      el.removeEventListener("wheel", onIntent);
      el.removeEventListener("touchmove", onIntent);
      el.removeEventListener("scroll", sync);
    };
  }, [scrollRef]);

  const jumpToLatest = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    stickRef.current = true;
    setShowJump(false);
    el.scrollTop = el.scrollHeight;
  }, [scrollRef]);

  return { showJump, jumpToLatest };
}

const SUGGESTIONS: { icon: IconSvgElement; label: string }[] = [
  { icon: FileSearchIcon, label: "Summarize this ontology" },
  { icon: GitPullRequestIcon, label: "Find classes with no definition" },
  {
    icon: CheckListIcon,
    label: "Draft a subclass axiom",
  },
];

const CANNED_REPLY =
  "This ontology defines 18 classes and 6 properties. Most classes carry a " +
  "label, but PizzaTemperada, PizzaVegetariana, and ValorDaParticipacao have " +
  "no definition yet. I can list every unlabeled class, propose definitions, " +
  "or draft a subclass axiom: tell me which class to start with.";

export default function AiChat3() {
  const reduced = useReducedMotion();
  const [messages, setMessages] = useState<Message[]>([]);
  const [liveId, setLiveId] = useState<string | null>(null);
  const [stopped, setStopped] = useState(false);
  const [value, setValue] = useState("");
  const [runId, setRunId] = useState(0);

  const { text: streamText, done, push, end, reset } = useSmoothedText();
  const {
    ref: transcriptRef,
    edges: transcriptEdges,
    onScroll: onTranscriptScroll,
  } = useScrollFade<HTMLDivElement>();
  const { showJump, jumpToLatest } = useStickToBottom(transcriptRef);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const transportRef = useRef<number | null>(null);

  const streaming = liveId !== null && !done && !stopped;
  const pending = streaming && streamText === "";

  useEffect(() => {
    if (!liveId) return;
    if (reduced) {
      push(CANNED_REPLY);
      end();
      return;
    }
    const chunks = CANNED_REPLY.match(/\S+\s*/g) ?? [];
    let i = 0;
    transportRef.current = window.setInterval(() => {
      const n = 1 + Math.floor(Math.random() * 3);
      push(chunks.slice(i, i + n).join(""));
      i += n;
      if (i >= chunks.length) {
        end();
        if (transportRef.current) window.clearInterval(transportRef.current);
        transportRef.current = null;
      }
    }, 130);
    return () => {
      if (transportRef.current) window.clearInterval(transportRef.current);
    };
  }, [liveId, runId, reduced, push, end]);

  const autosize = () => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  };

  const handleSend = useCallback(
    (text: string) => {
      const t = text.trim();
      if (!t) return;
      const uid = `u-${Date.now()}`;
      const aid = `a-${Date.now()}`;
      reset();
      setStopped(false);
      setMessages((m) => [
        ...m,
        { id: uid, role: "user", text: t },
        { id: aid, role: "assistant", text: CANNED_REPLY },
      ]);
      setLiveId(aid);
      setRunId((n) => n + 1);
      setValue("");
    },
    [reset],
  );

  const handleStop = useCallback(() => {
    if (transportRef.current) {
      window.clearInterval(transportRef.current);
      transportRef.current = null;
    }
    end();
    setStopped(true);
  }, [end]);

  const empty = messages.length === 0;

  return (
    <div
      className={cx(
        canvas,
        text1,
        "relative flex h-full min-h-0 w-full flex-col overflow-hidden p-3",
      )}
    >
      <div className={cx(panel, "w-full min-w-0 flex-1")}>
        <div className={cx(panelHead, "h-12")}>
          <span className={frameTitle}>Assist agent</span>
          <span className={cx(statusWord, statusIdle, "ml-auto")}>Ready</span>
        </div>

        <div className={cx(panelBody, "min-h-0 flex-1")}>
          <div className="relative flex min-h-0 flex-1 flex-col">
            <div
              ref={transcriptRef}
              onScroll={onTranscriptScroll}
              className="min-h-0 flex-1 overflow-y-auto overscroll-contain [overflow-anchor:none]"
            >
              <div className="flex min-h-full flex-col px-3 py-3">
                {empty ? (
                  <div className="m-auto w-full">
                    <div className="space-y-1.5">
                      <h2 className={emptyTitle}>How can I help?</h2>
                      <p className={emptyDescription}>
                        Ask about this ontology, or start from an example.
                      </p>
                    </div>
                    <div className={cx(rowsRuled, "-mx-2.5 mt-5")}>
                      {SUGGESTIONS.map(({ icon, label }) => (
                        <div key={label} className="p-1">
                          <button
                            type="button"
                            onClick={() => handleSend(label)}
                            className={cx(
                              itemRowSm,
                              itemRowInteractive,
                              cornerSm,
                              focusRingInset,
                              "px-1.5",
                            )}
                          >
                            <HugeiconsIcon
                              icon={icon}
                              size={16}
                              strokeWidth={1.8}
                              aria-hidden="true"
                              className={cx(text3, "shrink-0")}
                            />
                            <span
                              className={cx(
                                typeBodySm,
                                text2,
                                "min-w-0 flex-1 truncate",
                              )}
                            >
                              {label}
                            </span>
                          </button>
                        </div>
                      ))}
                    </div>
                  </div>
                ) : (
                  <div className="flex flex-col gap-4">
                    {messages.map((message) => {
                      if (message.role === "user") {
                        return (
                          <div key={message.id} className="flex">
                            <div className="ml-auto max-w-[85%]">
                              <div className={userBubble}>{message.text}</div>
                            </div>
                          </div>
                        );
                      }
                      const isLive = message.id === liveId;
                      const showSkeleton = isLive && pending;
                      return (
                        <div key={message.id} className="min-h-[1.25rem]">
                          {showSkeleton ? (
                            <div className={cx(activityLine, "h-5")}>
                              <span className={arcLine}>
                                <span
                                  className={runningArc}
                                  aria-hidden="true"
                                />
                              </span>
                              Thinking
                            </div>
                          ) : (
                            <p className={prose}>
                              {isLive && streaming ? streamText : message.text}
                            </p>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>

            <div
              aria-hidden="true"
              className={cx(
                fadeTop,
                "pointer-events-none absolute inset-x-0 top-0 h-8",
                transcriptEdges.start ? "opacity-100" : "opacity-0",
              )}
            />
            <div
              aria-hidden="true"
              className={cx(
                fadeBottom,
                "pointer-events-none absolute inset-x-0 bottom-0 h-8",
                transcriptEdges.end ? "opacity-100" : "opacity-0",
              )}
            />

            {showJump && (
              <div className="absolute bottom-3 left-1/2 z-10 -translate-x-1/2">
                <motion.button
                  type="button"
                  onClick={jumpToLatest}
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  transition={{ duration: 0.15 }}
                  className={cx(secondaryButton, buttonMd)}
                >
                  <HugeiconsIcon
                    icon={ArrowDown02Icon}
                    size={16}
                    strokeWidth={1.8}
                    aria-hidden="true"
                  />
                  Jump to latest
                </motion.button>
              </div>
            )}
          </div>
        </div>

        <div className="shrink-0 p-1">
          <div className={composer}>
            <div className={composerRow}>
              <textarea
                ref={textareaRef}
                rows={1}
                value={value}
                onChange={(e) => {
                  setValue(e.target.value);
                  autosize();
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    handleSend(value);
                  }
                }}
                placeholder="Ask anything"
                className={composerInput}
              />
              {streaming ? (
                <button
                  type="button"
                  onClick={handleStop}
                  key="stop"
                  aria-label="Stop generating"
                  className={cx(primaryButton, iconButtonSm)}
                >
                  <span
                    aria-hidden="true"
                    className="size-2.5 rounded-[1px] bg-current"
                  />
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => handleSend(value)}
                  key="send"
                  aria-label="Send"
                  disabled={value.trim().length === 0}
                  className={cx(primaryButton, iconButtonSm)}
                >
                  <HugeiconsIcon
                    icon={ArrowUp02Icon}
                    size={16}
                    strokeWidth={1.8}
                    aria-hidden="true"
                  />
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
