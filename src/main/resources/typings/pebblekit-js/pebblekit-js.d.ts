// PebbleKit JS: the JavaScript that runs on the phone, beside a Pebble app.
// From the reference at https://developer.repebble.com/docs/pebblekit-js/Pebble/ — the API has been
// stable since 2016, so this file is the plugin's own rather than generated.

/** An event delivered to a listener registered with `Pebble.addEventListener`. */
interface PebbleEvent {
    /** The event type: `ready`, `appmessage`, `showConfiguration` or `webviewclosed`. */
    type: string;
    /** For `appmessage`: the dictionary the watch sent, by message key. */
    payload: { [key: string]: any };
    /** For `webviewclosed`: what the configuration page put after the URL's anchor; may need `decodeURIComponent`. */
    response: string;
}

interface PebbleAppMessageData {
    transactionId: number;
}

interface PebbleWatchInfo {
    /** The hardware platform: `aplite`, `basalt`, `chalk`, `diorite`, `emery`, `flint` or `gabbro`. */
    platform: string;
    /** The watch model, such as `pebble_black`. */
    model: string;
    /** The language the user chose. */
    language: string;
    firmware: { major: number; minor: number; patch: number; suffix: string };
}

interface PebbleAppGlanceSlice {
    layout: { icon?: string; subtitleTemplateString: string };
    expirationTime?: string;
}

interface PebblePluginSubscription {
    unsubscribe(): void;
}

interface Pebble {
    /** The watchapp launched and PebbleKit JS is ready. */
    addEventListener(type: "ready", callback: (event: PebbleEvent) => void): void;
    /** The watch sent an AppMessage; its key-value pairs are in `payload`. */
    addEventListener(type: "appmessage", callback: (event: PebbleEvent) => void): void;
    /** The user asked for the app's configuration; call `Pebble.openURL` with the page. */
    addEventListener(type: "showConfiguration", callback: (event: PebbleEvent) => void): void;
    /** The configuration page closed; its answer is in `response`. */
    addEventListener(type: "webviewclosed", callback: (event: PebbleEvent) => void): void;
    addEventListener(type: string, callback: (event: PebbleEvent) => void): void;

    removeEventListener(type: string, callback: (event: PebbleEvent) => void): void;

    /** Shows a modal notification on the watch. */
    showSimpleNotificationOnPebble(title: string, body: string): void;

    /**
     * Sends a dictionary to the watch, by message key. Array values over 255 are taken modulo 255.
     * @returns the transaction ID
     */
    sendAppMessage(
        data: { [key: string]: any },
        onSuccess?: (data: PebbleAppMessageData) => void,
        onFailure?: (data: PebbleAppMessageData, error: string) => void,
    ): number;

    /** The timeline token; only apps installed from the appstore get one, sideloaded apps fail. */
    getTimelineToken(onSuccess: (token: string) => void, onFailure: () => void): void;
    timelineSubscribe(topic: string, onSuccess: () => void, onFailure: () => void): void;
    timelineUnsubscribe(topic: string, onSuccess: () => void, onFailure: () => void): void;
    timelineSubscriptions(onSuccess: (topics: string[]) => void, onFailure: () => void): void;
    /** Inserts a timeline pin, or updates the one with the same `id`. */
    insertTimelinePin(pin: object | string): void;
    deleteTimelinePin(id: string): void;

    getActiveWatchInfo(): PebbleWatchInfo;
    /** The user's account token, or `''` when they are not logged in. */
    getAccountToken(): string;
    /** A token unique to this app on this watch. */
    getWatchToken(): string;

    appGlanceReload(
        slices: PebbleAppGlanceSlice[],
        onSuccess: (slices: PebbleAppGlanceSlice[]) => void,
        onFailure: (slices: PebbleAppGlanceSlice[]) => void,
    ): void;

    /** Opens a configuration page; what a `showConfiguration` handler does. */
    openURL(url: string): void;

    /** Preview: the phone's plugins. */
    enumeratePlugins(): { uuid: string; name: string; sources: any[]; actions: any[] }[];
    /** Preview: data from a phone plugin's source. */
    subscribeToSource(config: {
        category: string;
        item: string;
        properties?: string[];
        plugin?: string;
        iconPixelSize?: { w: number; h: number };
        onData?: (data: any) => void;
        onError?: (error: { code: string; message: string }) => void;
    }): PebblePluginSubscription;
    /** Preview: runs a phone plugin's action. */
    invokeAction(config: { plugin: string; action: string; args?: object; timeoutMs?: number }): Promise<
        { ok: true; text: string; refreshed: boolean } | { ok: false; code: string; message: string }
    >;
    /** Preview: a message to the open settings page, which receives it as a `message` event. */
    sendConfigMessage(message: any): void;
}

declare var Pebble: Pebble;
