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
            })();
            """;
    private static boolean installed;
    private static CefMessageRouter messageRouter;
    private static final java.util.Map<CefBrowser, java.util.function.Consumer<String>> NEW_TAB_HANDLERS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

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
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (frame.isMain() && NEW_TAB_HANDLERS.containsKey(browser)) {
                    installTabHooks(browser);
                }
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

    public static void registerNewTabHandler(CefBrowser browser,
            java.util.function.Consumer<String> handler) {
        NEW_TAB_HANDLERS.put(browser, handler);
    }

    public static void unregisterNewTabHandler(CefBrowser browser) {
        NEW_TAB_HANDLERS.remove(browser);
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
