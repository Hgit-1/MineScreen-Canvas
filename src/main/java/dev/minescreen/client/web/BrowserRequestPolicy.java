package dev.minescreen.client.web;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

import com.cinemamod.mcef.MCEFClient;
import dev.minescreen.MineScreenConfig;
import dev.minescreen.client.ClientSecurityPolicy;
import org.cef.browser.CefMessageRouter;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

/** Applies the config policy to top-level navigation, redirects and subresources. */
public final class BrowserRequestPolicy {
    private static final String NEW_TAB_QUERY = "minescreen:newtab\n";
    private static final String POINTER_LOCK_QUERY = "minescreen:pointerlock\n";
    private static final String EXIT_POINTER_LOCK_SCRIPT = """
            (() => {
              try {
                if (document.exitPointerLock) document.exitPointerLock();
                else if (document.webkitExitPointerLock) document.webkitExitPointerLock();
              } catch (_) {}
            })();
            """;
    private static final String TAB_HOOK_SCRIPT = """
            (() => {
              if (window.__mineScreenTabHook) return;
              Object.defineProperty(window, '__mineScreenTabHook', {value: true});
              const send = value => {
                if (value == null || value === '' || value === 'about:blank') return;
                try {
                  const target = new URL(String(value), document.baseURI).href;
                  window.cefQuery({request: 'minescreen:newtab\\n' + target});
                } catch (_) {}
              };
              const popupProxy = () => {
                const location = {
                  assign: send,
                  replace: send,
                  reload: () => {}
                };
                Object.defineProperty(location, 'href', {get: () => '', set: send});
                const proxy = {closed: false, focus: () => {}, close() { this.closed = true; }};
                Object.defineProperty(proxy, 'location', {get: () => location, set: send});
                return proxy;
              };
              window.open = function(url) {
                send(url);
                return popupProxy();
              };
              document.addEventListener('click', event => {
                const anchor = event.target && event.target.closest
                    ? event.target.closest('a[href]') : null;
                if (!anchor) return;
                const target = (anchor.getAttribute('target') || '').toLowerCase();
                if (target === '_blank') {
                  event.preventDefault();
                  event.stopImmediatePropagation();
                  send(anchor.href);
                }
              }, true);
              document.addEventListener('submit', event => {
                const form = event.target;
                if (!form || (form.getAttribute('target') || '').toLowerCase() !== '_blank'
                    || (form.method || 'get').toLowerCase() !== 'get') return;
                event.preventDefault();
                event.stopImmediatePropagation();
                try {
                  const target = new URL(form.action || location.href, document.baseURI);
                  new FormData(form).forEach((value, key) => target.searchParams.append(key, value));
                  send(target.href);
                } catch (_) {}
              }, true);
              if (!window.__mineScreenPointerLockHook) {
                 Object.defineProperty(window, '__mineScreenPointerLockHook', {value: true});
                 let virtualPointerLockElement = null;
                 const nativePointerLockDescriptor =
                     Object.getOwnPropertyDescriptor(Document.prototype, 'pointerLockElement');
                 const nativePointerLockElement = () => {
                   try {
                     return nativePointerLockDescriptor && nativePointerLockDescriptor.get
                         ? nativePointerLockDescriptor.get.call(document) : null;
                   } catch (_) { return null; }
                 };
                 try {
                   Object.defineProperty(document, 'pointerLockElement', {
                     configurable: true,
                     get: () => virtualPointerLockElement || nativePointerLockElement()
                   });
                   Object.defineProperty(document, 'webkitPointerLockElement', {
                     configurable: true,
                     get: () => virtualPointerLockElement || nativePointerLockElement()
                   });
                 } catch (_) {}
                 const pointerLockState = locked => {
                   try {
                     window.cefQuery({request: 'minescreen:pointerlock\\n' + (locked ? '1' : '0')});
                   } catch (_) {}
                 };
                 const pointerLockChange = () => {
                   try { document.dispatchEvent(new Event('pointerlockchange')); } catch (_) {}
                 };
                 let previousPointerX = null;
                 let previousPointerY = null;
                 const wrappedDelta = (current, previous, extent) => {
                   if (previous == null) return 0;
                   let delta = current - previous;
                   if (extent > 4) {
                     if (delta > extent / 2) delta -= extent;
                     else if (delta < -extent / 2) delta += extent;
                   }
                   return delta;
                 };
                 window.addEventListener('mousemove', event => {
                   if (!virtualPointerLockElement) return;
                   const movementX = wrappedDelta(event.clientX, previousPointerX,
                       Math.max(1, window.innerWidth));
                   const movementY = wrappedDelta(event.clientY, previousPointerY,
                       Math.max(1, window.innerHeight));
                   previousPointerX = event.clientX;
                   previousPointerY = event.clientY;
                   try {
                     Object.defineProperty(event, 'movementX', {configurable: true, value: movementX});
                     Object.defineProperty(event, 'movementY', {configurable: true, value: movementY});
                   } catch (_) {}
                 }, true);
                 const requestPointerLock = function(...args) {
                   virtualPointerLockElement = this;
                   previousPointerX = null;
                   previousPointerY = null;
                   pointerLockState(true);
                   Promise.resolve().then(pointerLockChange);
                   // Do not call CEF's native OSR Pointer Lock here. JCEF bundled with MCEF 2.1.6
                   // has no permission handler and rejects it, after which cloud-game pages see a
                   // pointerlockerror and immediately tear down their controls. MineScreen owns
                   // the physical grabbed mouse and implements the complete browser-facing lock.
                   return Promise.resolve();
                 };
                 Element.prototype.requestPointerLock = requestPointerLock;
                 Element.prototype.webkitRequestPointerLock = requestPointerLock;
                 Document.prototype.exitPointerLock = function(...args) {
                   virtualPointerLockElement = null;
                   previousPointerX = null;
                   previousPointerY = null;
                   pointerLockState(false);
                   Promise.resolve().then(pointerLockChange);
                   return undefined;
                 };
                 Document.prototype.webkitExitPointerLock =
                     Document.prototype.exitPointerLock;
                 document.addEventListener('pointerlockchange', () => {
                   const nativeElement = nativePointerLockElement();
                   if (nativeElement) {
                     virtualPointerLockElement = nativeElement;
                     pointerLockState(true);
                   } else if (!virtualPointerLockElement) {
                     pointerLockState(false);
                   }
                 }, true);
                 // A stale/cached native API call can still make CEF emit an error. While the
                 // virtual lock is active, do not let that implementation detail tell the game
                 // that MineScreen's lock failed.
                 document.addEventListener('pointerlockerror', event => {
                   if (!virtualPointerLockElement) return;
                   event.preventDefault();
                   event.stopImmediatePropagation();
                   pointerLockState(true);
                 }, true);
               }
            })();
            """;
    private static boolean installed;
    private static CefMessageRouter messageRouter;
    private static final java.util.Map<CefBrowser, java.util.function.Consumer<String>> NEW_TAB_HANDLERS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<CefBrowser, java.util.function.Consumer<Boolean>>
            POINTER_LOCK_HANDLERS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<CefBrowser, java.util.function.Consumer<BrowserLoadState>>
            LOAD_STATE_HANDLERS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private BrowserRequestPolicy() {
    }

    public static synchronized void install(MCEFClient mcefClient) {
        if (installed) {
            return;
        }
        org.cef.CefClient client = mcefClient.getHandle();
        messageRouter = CefMessageRouter.create();
        messageRouter.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                    String request, boolean persistent, CefQueryCallback callback) {
                if (request != null && request.startsWith(POINTER_LOCK_QUERY)) {
                    java.util.function.Consumer<Boolean> handler = POINTER_LOCK_HANDLERS.get(browser);
                    if (handler == null) {
                        callback.failure(404, "MineScreen pointer-lock owner is unavailable");
                    } else {
                        handler.accept(request.substring(POINTER_LOCK_QUERY.length()).startsWith("1"));
                        callback.success("updated");
                    }
                    return true;
                }
                if (request == null || !request.startsWith(NEW_TAB_QUERY)) {
                    return false;
                }
                String target = request.substring(NEW_TAB_QUERY.length());
                java.util.function.Consumer<String> handler = NEW_TAB_HANDLERS.get(browser);
                if (handler == null || !isAllowedRequest(target)) {
                    callback.failure(403, "MineScreen blocked or cannot own this tab");
                } else {
                    handler.accept(target);
                    callback.success("queued");
                }
                return true;
            }
        }, true);
        client.addMessageRouter(messageRouter);
        mcefClient.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser browser, CefFrame frame,
                    CefRequest.TransitionType transitionType) {
                if (NEW_TAB_HANDLERS.containsKey(browser)) {
                    // Best-effort early injection lets MineScreen's capture listener precede game
                    // scripts that register mousemove handlers during iframe initialization.
                    installTabHooks(frame);
                }
                if (frame != null && frame.isMain() && !isChromiumErrorUrl(frame.getURL())) {
                    publishLoadState(browser, BrowserLoadState.loading(frame.getURL()));
                }
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (NEW_TAB_HANDLERS.containsKey(browser)) {
                    // Cloud-game canvases are commonly hosted in a cross-origin iframe. Each CEF
                    // frame owns its Window/Document, so injecting only the main frame misses the
                    // Pointer Lock request entirely.
                    installTabHooks(frame);
                }
                if (frame != null && frame.isMain()) {
                    String url = frame.getURL();
                    if (isChromiumErrorUrl(url)) {
                        return;
                    }
                    if (httpStatusCode >= 400) {
                        publishLoadState(browser, BrowserLoadState.error(httpStatusCode,
                                "HTTP " + httpStatusCode, url));
                    } else {
                        publishLoadState(browser, BrowserLoadState.ready(httpStatusCode, url));
                    }
                }
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame,
                    CefLoadHandler.ErrorCode errorCode, String errorText, String failedUrl) {
                if (frame == null || !frame.isMain()
                        || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) {
                    return;
                }
                publishLoadState(browser, BrowserLoadState.error(errorCode.getCode(),
                        errorCode.name() + (errorText == null || errorText.isBlank()
                                ? "" : ": " + errorText), failedUrl));
            }
        });
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            private final CefResourceRequestHandler resources = new CefResourceRequestHandlerAdapter() {
                @Override
                public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame, CefRequest request) {
                    return !isAllowedRequest(request.getURL());
                }

                @Override
                public void onResourceRedirect(CefBrowser browser, CefFrame frame, CefRequest request,
                        CefResponse response, StringRef newUrl) {
                    if (!isAllowedRequest(newUrl.get())) {
                        // Replacing the target prevents CEF from following a policy-breaking
                        // redirect. about:blank is local inert content, never a network bypass.
                        newUrl.set("about:blank");
                    }
                }
            };

            @Override
            public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request,
                    boolean userGesture, boolean redirect) {
                return !isAllowedRequest(request.getURL());
            }

            @Override
            public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame, String targetUrl,
                    boolean userGesture) {
                // target=_blank/window.open is converted into a managed MineScreen tab after the
                // same URL/IP policy check; the unmanaged CEF popup itself is always cancelled.
                if (isAllowedRequest(targetUrl)) {
                    java.util.function.Consumer<String> handler = NEW_TAB_HANDLERS.get(browser);
                    if (handler != null) {
                        handler.accept(targetUrl);
                    } else {
                        browser.loadURL(targetUrl);
                    }
                }
                return true;
            }

            @Override
            public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame,
                    CefRequest request, boolean navigation, boolean download, String initiator,
                    BoolRef disableDefaultHandling) {
                return resources;
            }
        });
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl,
                    String targetFrameName) {
                if (isAllowedRequest(targetUrl)) {
                    java.util.function.Consumer<String> handler = NEW_TAB_HANDLERS.get(browser);
                    if (handler != null) {
                        handler.accept(targetUrl);
                    }
                }
                // Never allow an unmanaged native/OSR popup. MineScreen owns every visible tab.
                return true;
            }
        });
        installed = true;
    }

    /** Installs the target=_blank/window.open bridge in the current main-frame document. */
    public static void installTabHooks(CefBrowser browser) {
        browser.executeJavaScript(TAB_HOOK_SCRIPT, browser.getURL(), 0);
    }

    public static void installTabHooks(CefFrame frame) {
        if (frame != null && frame.isValid()) {
            String url = frame.getURL();
            frame.executeJavaScript(TAB_HOOK_SCRIPT,
                    url == null || url.isBlank() ? "about:blank" : url, 0);
        }
    }

    /** Releases virtual/native locks in the main document and every cloud-game iframe. */
    public static void exitPointerLock(CefBrowser browser) {
        if (browser == null) {
            return;
        }
        java.util.Vector<Long> identifiers = browser.getFrameIdentifiers();
        if (identifiers == null || identifiers.isEmpty()) {
            CefFrame main = browser.getMainFrame();
            if (main != null && main.isValid()) {
                main.executeJavaScript(EXIT_POINTER_LOCK_SCRIPT,
                        main.getURL() == null ? "about:blank" : main.getURL(), 0);
            }
            return;
        }
        for (Long identifier : identifiers) {
            CefFrame frame = identifier == null ? null : browser.getFrame(identifier);
            if (frame != null && frame.isValid()) {
                String url = frame.getURL();
                frame.executeJavaScript(EXIT_POINTER_LOCK_SCRIPT,
                        url == null || url.isBlank() ? "about:blank" : url, 0);
            }
        }
    }

    public static void registerNewTabHandler(CefBrowser browser,
            java.util.function.Consumer<String> handler) {
        NEW_TAB_HANDLERS.put(browser, handler);
    }

    public static void unregisterNewTabHandler(CefBrowser browser) {
        NEW_TAB_HANDLERS.remove(browser);
    }

    public static void registerPointerLockHandler(CefBrowser browser,
            java.util.function.Consumer<Boolean> handler) {
        POINTER_LOCK_HANDLERS.put(browser, handler);
    }

    public static void unregisterPointerLockHandler(CefBrowser browser) {
        POINTER_LOCK_HANDLERS.remove(browser);
    }

    public static void registerLoadStateHandler(CefBrowser browser,
            java.util.function.Consumer<BrowserLoadState> handler) {
        LOAD_STATE_HANDLERS.put(browser, handler);
    }

    public static void unregisterLoadStateHandler(CefBrowser browser) {
        LOAD_STATE_HANDLERS.remove(browser);
    }

    private static void publishLoadState(CefBrowser browser, BrowserLoadState state) {
        java.util.function.Consumer<BrowserLoadState> handler = LOAD_STATE_HANDLERS.get(browser);
        if (handler != null) {
            handler.accept(state);
        }
    }

    private static boolean isChromiumErrorUrl(String url) {
        return url != null && (url.startsWith("chrome-error://")
                || url.startsWith("chrome://network-error/"));
    }

    /** Main-frame state only; iframe failures must not replace an otherwise usable page. */
    public record BrowserLoadState(boolean loading, boolean failed, int statusCode,
            String message, String url) {
        static BrowserLoadState loading(String url) {
            return new BrowserLoadState(true, false, 0, "", clean(url));
        }

        static BrowserLoadState ready(int statusCode, String url) {
            return new BrowserLoadState(false, false, statusCode, "", clean(url));
        }

        static BrowserLoadState error(int statusCode, String message, String url) {
            return new BrowserLoadState(false, true, statusCode, clean(message), clean(url));
        }

        private static String clean(String value) {
            return value == null ? "" : value;
        }
    }

    public static boolean isAllowed(String value) {
        if (ClientSecurityPolicy.unrestrictedSingleplayer()) {
            return true;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("file")) {
                return MineScreenConfig.ALLOW_FILE_PROTOCOL.get();
            }
            if (!scheme.equals("https") && !(scheme.equals("http") && MineScreenConfig.ALLOW_HTTP.get())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            boolean localhost = normalized.equals("localhost") || normalized.endsWith(".localhost");
            if (localhost && !MineScreenConfig.ALLOW_LOCALHOST.get()) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            boolean specialAddress = localhost;
            for (InetAddress address : addresses) {
                byte[] bytes = address.getAddress();
                boolean cloudMetadata = bytes.length == 4 && (bytes[0] & 0xFF) == 169
                        && (bytes[1] & 0xFF) == 254 && (bytes[2] & 0xFF) == 169
                        && (bytes[3] & 0xFF) == 254;
                if (cloudMetadata && !MineScreenConfig.ALLOW_CLOUD_METADATA.get()) {
                    return false;
                }
                specialAddress |= cloudMetadata;
                if ((address.isLoopbackAddress() || address.isAnyLocalAddress())
                        && !MineScreenConfig.ALLOW_LOCALHOST.get()) {
                    return false;
                }
                specialAddress |= address.isLoopbackAddress() || address.isAnyLocalAddress();
                if ((address.isSiteLocalAddress() || address.isLinkLocalAddress())
                        && !MineScreenConfig.ALLOW_PRIVATE_IP.get() && !cloudMetadata) {
                    return false;
                }
                specialAddress |= address.isSiteLocalAddress() || address.isLinkLocalAddress();
                boolean ipv6UniqueLocal = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
                if (ipv6UniqueLocal && !MineScreenConfig.ALLOW_PRIVATE_IP.get()) {
                    return false;
                }
                specialAddress |= ipv6UniqueLocal;
            }
            // Explicit localhost/private/metadata unlocks work for their literal/special host
            // without also requiring disable_whitelist. Ordinary public domains still require an
            // allow-list entry unless disable_whitelist is enabled.
            if (!MineScreenConfig.DISABLE_WHITELIST.get() && !whitelisted(normalized)
                    && !(specialAddress && (localhost || isIpLiteral(normalized)))) {
                return false;
            }
            return true;
        } catch (RuntimeException | java.net.UnknownHostException exception) {
            return false;
        }
    }

    /** Policy used by Chromium after an approved page is running, including renderer-local URLs. */
    public static boolean isAllowedRequest(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            String scheme = URI.create(value).getScheme();
            if (scheme != null) {
                scheme = scheme.toLowerCase(Locale.ROOT);
                // These are renderer-local URLs created by an already-approved page. Allowing them
                // restores normal tab widgets, blob downloads/previews, and javascript links while
                // the editor still refuses them as a top-level configured source.
                if (scheme.equals("blob") || scheme.equals("data") || scheme.equals("javascript")) {
                    return true;
                }
                if (scheme.equals("about")) {
                    return value.equalsIgnoreCase("about:blank");
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return isAllowed(value);
    }

    private static boolean whitelisted(String host) {
        for (String entry : MineScreenConfig.DOMAIN_WHITELIST.get()) {
            String allowed = entry.toLowerCase(Locale.ROOT).trim();
            if (!allowed.isEmpty() && (host.equals(allowed) || host.endsWith("." + allowed))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char character = host.charAt(i);
            if ((character < '0' || character > '9') && character != '.') {
                return false;
            }
        }
        return !host.isEmpty();
    }
}
