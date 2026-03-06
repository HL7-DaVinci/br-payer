import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const keys = new WeakMap<object, string>();

/** Returns a stable unique key for any object, suitable for React list keys. */
export function keyOf(obj: object): string {
  let k = keys.get(obj);
  if (!k) {
    k = crypto.randomUUID();
    keys.set(obj, k);
  }
  return k;
}
