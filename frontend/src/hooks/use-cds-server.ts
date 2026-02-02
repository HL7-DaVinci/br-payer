import { useCallback, useState, useSyncExternalStore } from "react";
import {
  CDS_SERVERS,
  type CdsServer,
  getCdsServerByUrl,
  getStoredCdsServerUrl,
  setStoredCdsServerUrl,
} from "@/lib/cds-config";

export interface UseCdsServerResult {
  serverUrl: string;
  server: CdsServer | undefined;
  presetServers: CdsServer[];
  setServerUrl: (url: string) => void;
  isCustomServer: boolean;
}

const cdsServerUrlStore = {
  listeners: new Set<() => void>(),

  getSnapshot(): string {
    return getStoredCdsServerUrl();
  },

  getServerSnapshot(): string {
    return CDS_SERVERS[0]?.url ?? "http://localhost:8080";
  },

  subscribe(listener: () => void): () => void {
    cdsServerUrlStore.listeners.add(listener);
    return () => cdsServerUrlStore.listeners.delete(listener);
  },

  emit(): void {
    for (const listener of cdsServerUrlStore.listeners) {
      listener();
    }
  },

  setServerUrl(url: string): void {
    setStoredCdsServerUrl(url);
    cdsServerUrlStore.emit();
  },
};

export function useCdsServer(): UseCdsServerResult {
  const serverUrl = useSyncExternalStore(
    cdsServerUrlStore.subscribe,
    cdsServerUrlStore.getSnapshot,
    cdsServerUrlStore.getServerSnapshot,
  );

  const setServerUrl = useCallback((url: string) => {
    cdsServerUrlStore.setServerUrl(url);
  }, []);

  const server = getCdsServerByUrl(serverUrl);
  const isCustomServer = !server;

  return {
    serverUrl,
    server,
    presetServers: CDS_SERVERS,
    setServerUrl,
    isCustomServer,
  };
}

export interface UseCdsServerSelectionResult {
  customUrl: string;
  setCustomUrl: (url: string) => void;
  showCustomInput: boolean;
  isEditing: boolean;
  handleServerChange: (value: string) => void;
  handleCustomUrlSubmit: () => void;
}

export function useCdsServerSelection(
  setServerUrl: (url: string) => void,
  isCustomServer: boolean,
  currentServerUrl: string,
): UseCdsServerSelectionResult {
  const [customUrl, setCustomUrl] = useState("");
  const [showCustomInput, setShowCustomInput] = useState(false);
  const [isEditing, setIsEditing] = useState(false);

  const startEditing = useCallback(() => {
    if (isCustomServer && !isEditing) {
      setCustomUrl(currentServerUrl);
      setIsEditing(true);
    }
  }, [isCustomServer, isEditing, currentServerUrl]);

  const handleServerChange = useCallback(
    (value: string) => {
      if (value === "custom") {
        setShowCustomInput(true);
        setCustomUrl("");
        setIsEditing(false);
      } else {
        setShowCustomInput(false);
        setIsEditing(false);
        setServerUrl(value);
      }
    },
    [setServerUrl],
  );

  const handleCustomUrlSubmit = useCallback(() => {
    if (customUrl.trim()) {
      setServerUrl(customUrl.trim().replace(/\/+$/, ""));
      setShowCustomInput(false);
      setIsEditing(false);
      setCustomUrl("");
    }
  }, [customUrl, setServerUrl]);

  const showInput = showCustomInput || isCustomServer;

  return {
    customUrl: isCustomServer && !isEditing ? currentServerUrl : customUrl,
    setCustomUrl: (url: string) => {
      startEditing();
      setCustomUrl(url);
    },
    showCustomInput: showInput,
    isEditing: isEditing || showCustomInput,
    handleServerChange,
    handleCustomUrlSubmit,
  };
}
