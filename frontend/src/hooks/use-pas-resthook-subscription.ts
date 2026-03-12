import { useCallback, useEffect, useRef, useState } from "react";
import type {
  RestHookSubscriptionStatus,
  TimelineEntry,
} from "@/lib/pas-types";
import { parseNotificationBundle } from "@/lib/pas-types";

export type RestHookEndpointMode = "inbox" | "custom";

interface UsePasResthookSubscriptionOptions {
  serverUrl: string;
  onNotification: (entry: TimelineEntry) => void;
}

interface UsePasResthookSubscriptionResult {
  status: RestHookSubscriptionStatus;
  npi: string;
  setNpi: (npi: string) => void;
  endpointMode: RestHookEndpointMode;
  setEndpointMode: (mode: RestHookEndpointMode) => void;
  customEndpoint: string;
  setCustomEndpoint: (url: string) => void;
  pollIntervalSeconds: number;
  setPollIntervalSeconds: (seconds: number) => void;
  connect: () => void;
  disconnect: () => void;
  error: string | null;
  subscriptionId: string | null;
}

interface SubscriptionHandle {
  serverUrl: string;
  subscriptionId: string;
}

const ACTIVATION_POLL_MS = 500;
const ACTIVATION_TIMEOUT_MS = 15_000;
const DEFAULT_POLL_INTERVAL_S = 3;
const CANCELLED_ERROR = "Cancelled REST hook subscription connection";

function createCancelledError(): Error {
  const error = new Error(CANCELLED_ERROR);
  error.name = "PasResthookCancelledError";
  return error;
}

function isCancelledError(error: unknown): boolean {
  return (
    error instanceof Error &&
    error.name === "PasResthookCancelledError" &&
    error.message === CANCELLED_ERROR
  );
}

function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function buildResthookSubscriptionResource(
  npi: string,
  channelEndpoint: string,
): object {
  return {
    resourceType: "Subscription",
    meta: {
      profile: [
        "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-subscription",
        "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-subscription",
      ],
    },
    status: "requested",
    reason: "Monitor PAS authorization notifications via REST hook",
    criteria:
      "http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic",
    _criteria: {
      extension: [
        {
          url: "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-filter-criteria",
          valueString: `Bundle?orgIdentifier=${npi}`,
        },
      ],
    },
    channel: {
      type: "rest-hook",
      endpoint: channelEndpoint,
      payload: "application/fhir+json",
      _payload: {
        extension: [
          {
            url: "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-payload-content",
            valueCode: "full-resource",
          },
        ],
      },
    },
  };
}

function deriveInboxUrl(serverUrl: string): string {
  const url = new URL(serverUrl);
  // Strip the /fhir path to get the app base URL
  const basePath = url.pathname.replace(/\/fhir\/?$/, "");
  return `${url.origin}${basePath}/api/pas/resthook-inbox`;
}

async function waitForSubscriptionActive(
  serverUrl: string,
  subscriptionId: string,
  isCurrentAttempt: () => boolean,
): Promise<void> {
  const deadline = Date.now() + ACTIVATION_TIMEOUT_MS;

  while (Date.now() < deadline) {
    if (!isCurrentAttempt()) throw createCancelledError();

    const res = await fetch(`${serverUrl}/Subscription/${subscriptionId}`, {
      headers: { Accept: "application/fhir+json" },
    });

    if (!isCurrentAttempt()) throw createCancelledError();

    if (res.ok) {
      const sub = await res.json();
      if (!isCurrentAttempt()) throw createCancelledError();
      if (sub.status === "active") return;
    }

    await pause(ACTIVATION_POLL_MS);
  }

  if (!isCurrentAttempt()) throw createCancelledError();
  throw new Error(
    "Subscription was not activated by the server within the timeout period",
  );
}

export function usePasResthookSubscription({
  serverUrl,
  onNotification,
}: UsePasResthookSubscriptionOptions): UsePasResthookSubscriptionResult {
  const [status, setStatus] = useState<RestHookSubscriptionStatus>("idle");
  const [npi, setNpi] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [subscriptionId, setSubscriptionId] = useState<string | null>(null);
  const [endpointMode, setEndpointMode] =
    useState<RestHookEndpointMode>("inbox");
  const [customEndpoint, setCustomEndpoint] = useState("");
  const [pollIntervalSeconds, setPollIntervalSeconds] = useState(
    DEFAULT_POLL_INTERVAL_S,
  );

  const onNotificationRef = useRef(onNotification);
  onNotificationRef.current = onNotification;

  const connectionAttemptRef = useRef(0);
  const activeSubscriptionRef = useRef<SubscriptionHandle | null>(null);
  const pollingTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const isPollingRef = useRef(false);
  const lastSequenceRef = useRef(0);
  // Track the endpoint mode at subscription time for polling decisions
  const activeEndpointModeRef = useRef<RestHookEndpointMode>("inbox");

  const deleteSubscription = useCallback(
    (handle: SubscriptionHandle | null) => {
      if (!handle) return;
      fetch(`${handle.serverUrl}/Subscription/${handle.subscriptionId}`, {
        method: "DELETE",
      }).catch(() => {});
    },
    [],
  );

  const clearInbox = useCallback((handle: SubscriptionHandle | null) => {
    if (!handle) return;
    const inboxUrl = deriveInboxUrl(handle.serverUrl);
    fetch(
      `${inboxUrl}?subscriptionId=${encodeURIComponent(handle.subscriptionId)}`,
      { method: "DELETE" },
    ).catch(() => {});
  }, []);

  const stopPolling = useCallback(() => {
    if (pollingTimerRef.current !== null) {
      clearInterval(pollingTimerRef.current);
      pollingTimerRef.current = null;
    }
  }, []);

  const clearTrackedSubscription = useCallback(() => {
    const handle = activeSubscriptionRef.current;
    activeSubscriptionRef.current = null;
    setSubscriptionId(null);
    return handle;
  }, []);

  const cleanup = useCallback(() => {
    connectionAttemptRef.current += 1;
    stopPolling();
    const handle = clearTrackedSubscription();
    clearInbox(handle);
    deleteSubscription(handle);
    lastSequenceRef.current = 0;
    setError(null);
    setStatus("idle");
  }, [clearTrackedSubscription, stopPolling, clearInbox, deleteSubscription]);

  const disconnect = useCallback(() => {
    cleanup();
  }, [cleanup]);

  // Polling function for inbox mode
  const pollInbox = useCallback(
    async (sUrl: string, subId: string, attemptId: number) => {
      if (isPollingRef.current) return;
      if (connectionAttemptRef.current !== attemptId) return;

      isPollingRef.current = true;
      try {
        const inboxUrl = deriveInboxUrl(sUrl);
        const res = await fetch(
          `${inboxUrl}?subscriptionId=${encodeURIComponent(subId)}&after=${lastSequenceRef.current}`,
        );

        if (connectionAttemptRef.current !== attemptId) return;
        if (!res.ok) return;

        const data = await res.json();
        if (connectionAttemptRef.current !== attemptId) return;

        const notifications = data.notifications as Array<{
          sequence: number;
          payload: unknown;
        }>;
        if (data.lastSequence > lastSequenceRef.current) {
          lastSequenceRef.current = data.lastSequence;
        }

        for (const n of notifications) {
          const entry = parseNotificationBundle(n.payload, "rest-hook");
          if (entry) {
            onNotificationRef.current(entry);
          }
        }
      } catch {
        // Network error during poll, will retry next interval
      } finally {
        isPollingRef.current = false;
      }
    },
    [],
  );

  // Start/restart polling when interval changes while active
  useEffect(() => {
    if (status !== "active") return;
    if (activeEndpointModeRef.current !== "inbox") return;

    const handle = activeSubscriptionRef.current;
    if (!handle) return;

    const attemptId = connectionAttemptRef.current;

    stopPolling();
    pollingTimerRef.current = setInterval(
      () => pollInbox(handle.serverUrl, handle.subscriptionId, attemptId),
      pollIntervalSeconds * 1000,
    );

    return () => stopPolling();
  }, [status, pollIntervalSeconds, stopPolling, pollInbox]);

  const connect = useCallback(async () => {
    if (!npi.trim()) {
      setError("NPI is required");
      setStatus("error");
      return;
    }

    cleanup();

    setError(null);
    setStatus("creating");

    const attemptId = connectionAttemptRef.current;
    const connectServerUrl = serverUrl;
    const mode = endpointMode;
    activeEndpointModeRef.current = mode;
    const isCurrentAttempt = () => connectionAttemptRef.current === attemptId;

    try {
      const channelEndpoint =
        mode === "inbox"
          ? deriveInboxUrl(connectServerUrl)
          : customEndpoint.trim();

      if (!channelEndpoint) {
        throw new Error("Custom endpoint URL is required");
      }

      const subscriptionResource = buildResthookSubscriptionResource(
        npi.trim(),
        channelEndpoint,
      );
      const response = await fetch(`${connectServerUrl}/Subscription`, {
        method: "POST",
        headers: { "Content-Type": "application/fhir+json" },
        body: JSON.stringify(subscriptionResource),
      });

      if (!response.ok) {
        const body = await response.text();
        throw new Error(
          `Failed to create Subscription: ${response.status} ${body}`,
        );
      }

      const created = await response.json();
      const subId = created.id;
      if (!subId) {
        throw new Error("Subscription created but no ID returned");
      }

      if (!isCurrentAttempt()) {
        deleteSubscription({
          serverUrl: connectServerUrl,
          subscriptionId: subId,
        });
        return;
      }

      activeSubscriptionRef.current = {
        serverUrl: connectServerUrl,
        subscriptionId: subId,
      };
      setSubscriptionId(subId);
      setStatus("activating");

      await waitForSubscriptionActive(
        connectServerUrl,
        subId,
        isCurrentAttempt,
      );

      if (!isCurrentAttempt()) return;

      setStatus("active");

      // Start inbox polling if in inbox mode
      if (mode === "inbox") {
        lastSequenceRef.current = 0;
        pollingTimerRef.current = setInterval(
          () => pollInbox(connectServerUrl, subId, attemptId),
          pollIntervalSeconds * 1000,
        );
      }
    } catch (err) {
      if (!isCurrentAttempt()) return;

      stopPolling();
      deleteSubscription(clearTrackedSubscription());

      if (isCancelledError(err)) return;

      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      setStatus("error");
    }
  }, [
    cleanup,
    clearTrackedSubscription,
    customEndpoint,
    deleteSubscription,
    endpointMode,
    npi,
    pollInbox,
    pollIntervalSeconds,
    serverUrl,
    stopPolling,
  ]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      connectionAttemptRef.current += 1;
      stopPolling();
      const handle = activeSubscriptionRef.current;
      activeSubscriptionRef.current = null;
      clearInbox(handle);
      deleteSubscription(handle);
    };
  }, [clearInbox, deleteSubscription, stopPolling]);

  return {
    status,
    npi,
    setNpi,
    endpointMode,
    setEndpointMode,
    customEndpoint,
    setCustomEndpoint,
    pollIntervalSeconds,
    setPollIntervalSeconds,
    connect,
    disconnect,
    error,
    subscriptionId,
  };
}
