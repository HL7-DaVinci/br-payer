import type { RefObject } from "react";
import { useEffect } from "react";

/**
 * Closes a dropdown/popover when the user clicks outside the referenced element.
 * Only attaches the listener while `isActive` is true.
 */
export function useClickOutside(
  ref: RefObject<HTMLElement | null>,
  onClickOutside: () => void,
  isActive: boolean,
): void {
  useEffect(() => {
    if (!isActive) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        onClickOutside();
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [isActive, ref, onClickOutside]);
}
