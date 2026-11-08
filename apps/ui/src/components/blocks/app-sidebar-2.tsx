"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react";
import {
  Activity01Icon,
  BlocksIcon,
  LifebuoyIcon,
  DashboardSpeed01Icon,
  DatabaseIcon,
  FlowConnectionIcon,
  Key01Icon,
  BoxesIcon,
  Rocket01Icon,
  ServerStack01Icon,
  Shield01Icon,
} from "@hugeicons/core-free-icons";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

const canvas = "bg-white dark:bg-neutral-950";
const text1 = "text-neutral-900 dark:text-neutral-100";
const text3 = "text-neutral-900/66 dark:text-neutral-100/66";
const typeLabel = "text-[14px]/[16px] font-[450]";
const typeCaption = "text-[12px]/[16px] font-normal tabular-nums";
const focusRing =
  "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";
const brandMark =
  "inline-flex shrink-0 items-center justify-center overflow-hidden rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] text-[var(--rb-accent-fg,oklch(100%_0_0))] select-none dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]";
const avatar =
  "relative inline-flex size-8 shrink-0 overflow-hidden rounded-full select-none";
const avatarFallback =
  "flex size-full items-center justify-center rounded-[inherit] border border-neutral-900/12 dark:border-neutral-100/14 bg-white dark:bg-neutral-900 text-[12px]/[16px] font-[450] tabular-nums text-neutral-900 dark:text-neutral-100";
const menuPopup =
  "min-w-48 max-w-[min(480px,calc(100vw-2rem))] overflow-x-hidden overflow-y-auto overscroll-contain rounded-[var(--rb-r-lg,10px)] border border-neutral-900/10 dark:border-neutral-100/14 bg-white dark:bg-neutral-900 p-1 text-neutral-900 dark:text-neutral-100 outline-none shadow-[0_12px_24px_-12px_rgb(0_0_0/0.2)] dark:shadow-[0_12px_24px_-12px_rgb(0_0_0/0.6)]";
const menuItem =
  "relative flex min-h-8 w-full cursor-default items-center gap-2 rounded-[var(--rb-r-sm,6px)] px-2 py-1.5 text-left text-[14px]/[16px] font-[450] text-neutral-900 outline-none select-none [&_svg]:shrink-0 [&_svg]:text-neutral-900/80 hover:bg-neutral-900/5 focus-visible:bg-neutral-900/5 data-[highlighted]:bg-neutral-900/5 aria-selected:bg-neutral-900/9 aria-disabled:cursor-not-allowed aria-disabled:text-neutral-900/40 dark:text-neutral-100 dark:[&_svg]:text-neutral-100/80 dark:hover:bg-neutral-100/5 dark:focus-visible:bg-neutral-100/5 dark:data-[highlighted]:bg-neutral-100/5 dark:aria-selected:bg-neutral-100/9 dark:aria-disabled:text-neutral-100/40";
const menuLabel =
  "px-2 pt-2 pb-1 text-[12px]/[16px] font-normal tabular-nums text-neutral-900/66 dark:text-neutral-100/66";
const sidebarSurface =
  "border-r border-neutral-900/8 bg-white dark:border-neutral-100/8 dark:bg-neutral-900";
const navItem =
  "flex h-8 w-full min-w-0 cursor-pointer items-center gap-2 rounded-[var(--rb-r-sm,6px)] px-2 text-left text-[14px]/[16px] font-[450] text-neutral-900/80 transition-[background-color,color] duration-[120ms] ease-[cubic-bezier(0.2,0,0,1)] [&_svg]:shrink-0 hover:bg-neutral-900/5 hover:text-neutral-900 aria-[current=page]:bg-neutral-900/9 aria-[current=page]:text-neutral-900 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))] dark:text-neutral-100/80 dark:hover:bg-neutral-100/5 dark:hover:text-neutral-100 dark:aria-[current=page]:bg-neutral-100/9 dark:aria-[current=page]:text-neutral-100";

export type RailItem = {
  label: string;
  icon: IconSvgElement;
  current?: boolean;
  children: string[];
};

const railGroups: RailItem[][] = [
  [
    {
      label: "Overview",
      icon: DashboardSpeed01Icon,
      children: ["Today", "This week"],
    },
    {
      label: "Automation",
      icon: FlowConnectionIcon,
      current: true,
      children: ["Agent runs", "Playbooks", "Schedules"],
    },
    {
      label: "Services",
      icon: BoxesIcon,
      children: ["Inventory", "Vendors", "Contracts"],
    },
    {
      label: "Data",
      icon: DatabaseIcon,
      children: ["Warehouses", "Exports", "Retention"],
    },
    {
      label: "Integrations",
      icon: BlocksIcon,
      children: ["Connected apps", "Webhooks", "API keys"],
    },
  ],
  [
    {
      label: "Governance",
      icon: Shield01Icon,
      children: ["Access review", "Audit log", "Policies"],
    },
    {
      label: "Monitoring",
      icon: Activity01Icon,
      children: ["Incidents", "Alerts", "Uptime"],
    },
    {
      label: "Releases",
      icon: Rocket01Icon,
      children: ["Rollouts", "Changelog", "Flags"],
    },
    {
      label: "Environments",
      icon: ServerStack01Icon,
      children: ["Production", "Staging", "Sandbox"],
    },
    {
      label: "Credentials",
      icon: Key01Icon,
      children: ["Tokens", "Certificates", "Secrets"],
    },
  ],
];

function Flyout({
  label,
  items,
  align,
  menuRef,
  onSelect,
}: {
  label: string;
  items: string[];
  align: "top" | "bottom";
  menuRef?: React.RefObject<HTMLDivElement>;
  onSelect?: (label: string, item: string) => void;
}) {
  return (
    <div
      ref={menuRef as React.RefObject<HTMLDivElement>}
      className={cx(
        "absolute left-full z-30 pl-2",
        align === "top" ? "top-0" : "bottom-0",
      )}
    >
      <div
        role="menu"
        aria-label={label}
        className={cx(menuPopup, "min-w-[176px]")}
      >
        <p className={menuLabel}>{label}</p>
        {items.map((child) => (
          <button
            key={child}
            type="button"
            role="menuitem"
            className={menuItem}
            onClick={() => onSelect?.(label, child)}
          >
            <span className="truncate">{child}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

export default function AppSidebar2({
  groups,
  onSelect,
  brand = "A",
}: {
  groups?: RailItem[][];
  onSelect?: (label: string, item: string) => void;
  brand?: string;
} = {}) {
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [pinned, setPinned] = useState(false);
  const railRef = useRef<HTMLElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRefs = useRef<Record<string, HTMLButtonElement | null>>({});

  const close = useCallback(
    (restoreFocus: boolean) => {
      const key = openKey;
      setOpenKey(null);
      setPinned(false);
      if (restoreFocus && key)
        triggerRefs.current[key]?.focus({ preventScroll: true });
    },
    [openKey],
  );

  const hoverOpen = (key: string, event: React.PointerEvent) => {
    if (event.pointerType !== "mouse" || pinned) return;
    setOpenKey(key);
  };

  const hoverClose = (key: string, event: React.PointerEvent) => {
    if (event.pointerType !== "mouse" || pinned) return;
    setOpenKey((cur) => (cur === key ? null : cur));
  };

  const toggle = (key: string) => {
    if (openKey === key && pinned) {
      setOpenKey(null);
      setPinned(false);
      return;
    }
    setOpenKey(key);
    setPinned(true);
  };

  useEffect(() => {
    if (!openKey) return;
    const doc = railRef.current?.ownerDocument ?? document;

    const onPointerDown = (event: Event) => {
      if (!railRef.current?.contains(event.target as Node)) close(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        close(true);
        return;
      }
      const items = Array.from(
        menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ??
          [],
      );
      if (items.length === 0) return;
      const index = items.indexOf(doc.activeElement as HTMLElement);
      if (event.key === "ArrowDown") {
        event.preventDefault();
        items[index < 0 || index === items.length - 1 ? 0 : index + 1]?.focus({
          preventScroll: true,
        });
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        items[index <= 0 ? items.length - 1 : index - 1]?.focus({
          preventScroll: true,
        });
      } else if (event.key === "Home") {
        event.preventDefault();
        items[0]?.focus({ preventScroll: true });
      } else if (event.key === "End") {
        event.preventDefault();
        items[items.length - 1]?.focus({ preventScroll: true });
      }
    };

    doc.addEventListener("pointerdown", onPointerDown);
    doc.addEventListener("keydown", onKeyDown);
    return () => {
      doc.removeEventListener("pointerdown", onPointerDown);
      doc.removeEventListener("keydown", onKeyDown);
    };
  }, [openKey, close]);

  return (
    <div
      className={cx(
        canvas,
        text1,
        "relative flex h-full min-h-[640px] w-full overflow-hidden",
      )}
    >
      <aside
        ref={railRef}
        className={cx(
          sidebarSurface,
          "flex w-14 shrink-0 flex-col items-center py-3",
        )}
      >
        <span className={cx(brandMark, "size-8", typeLabel)}>{brand}</span>

        <nav
          aria-label="Primary"
          className="mt-4 flex flex-1 flex-col items-center gap-4"
        >
          {(groups ?? railGroups).map((group, groupIndex) => (
            <ul
              key={groupIndex}
              className="flex flex-col items-center gap-1"
              role="list"
            >
              {group.map((item) => {
                const open = openKey === item.label;
                return (
                  <li
                    key={item.label}
                    className="relative"
                    onPointerEnter={(event) => hoverOpen(item.label, event)}
                    onPointerLeave={(event) => hoverClose(item.label, event)}
                  >
                    <button
                      ref={(node) => {
                        triggerRefs.current[item.label] = node;
                      }}
                      type="button"
                      aria-label={item.label}
                      aria-current={item.current ? "page" : undefined}
                      aria-haspopup="menu"
                      aria-expanded={open}
                      onClick={() => toggle(item.label)}
                      className={cx(navItem, "justify-center")}
                    >
                      <HugeiconsIcon
                        icon={item.icon}
                        size={16}
                        strokeWidth={1.8}
                        aria-hidden="true"
                      />
                    </button>
                    {open && (
                      <Flyout
                        menuRef={menuRef}
                        label={item.label}
                        items={item.children}
                        align="top"
                        onSelect={onSelect}
                      />
                    )}
                  </li>
                );
              })}
            </ul>
          ))}
        </nav>

        <div className="mt-4 flex flex-col items-center gap-1">
          {(
            [
              {
                label: "Support",
                icon: LifebuoyIcon,
                children: ["Help center", "Contact support", "Status page"],
              },
            ] as const
          ).map((item) => {
            const open = openKey === item.label;
            return (
              <div
                key={item.label}
                className="relative"
                onPointerEnter={(event) => hoverOpen(item.label, event)}
                onPointerLeave={(event) => hoverClose(item.label, event)}
              >
                <button
                  ref={(node) => {
                    triggerRefs.current[item.label] = node;
                  }}
                  type="button"
                  aria-label={item.label}
                  aria-haspopup="menu"
                  aria-expanded={open}
                  onClick={() => toggle(item.label)}
                  className={cx(navItem, "justify-center")}
                >
                  <HugeiconsIcon
                    icon={item.icon}
                    size={16}
                    strokeWidth={1.8}
                    aria-hidden="true"
                  />
                </button>
                {open && (
                  <Flyout
                    menuRef={menuRef}
                    label={item.label}
                    items={[...item.children]}
                    align="bottom"
                  />
                )}
              </div>
            );
          })}

          <div
            className="relative"
            onPointerEnter={(event) => hoverOpen("Account", event)}
            onPointerLeave={(event) => hoverClose("Account", event)}
          >
            <button
              ref={(node) => {
                triggerRefs.current.Account = node;
              }}
              type="button"
              aria-label="Your account"
              aria-haspopup="menu"
              aria-expanded={openKey === "Account"}
              onClick={() => toggle("Account")}
              className={cx(avatar, focusRing, "cursor-pointer")}
            >
              <span className={avatarFallback}>TS</span>
            </button>
            {openKey === "Account" && (
              <div
                ref={menuRef}
                className="absolute bottom-0 left-full z-30 pl-2"
              >
                <div
                  role="menu"
                  aria-label="Account"
                  className={cx(menuPopup, "w-[224px]")}
                >
                  <div className="px-2 py-1.5">
                    <p className={cx(typeLabel, text1, "truncate")}>
                      Theo Salcedo
                    </p>
                    <p className={cx(typeCaption, text3, "truncate")}>
                      theo@northwind.co
                    </p>
                  </div>
                  {["Account settings", "Preferences", "Sign out"].map(
                    (label) => (
                      <button
                        key={label}
                        type="button"
                        role="menuitem"
                        className={menuItem}
                      >
                        <span className="truncate">{label}</span>
                      </button>
                    ),
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      </aside>
    </div>
  );
}
