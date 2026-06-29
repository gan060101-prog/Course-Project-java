package minishop.web;

import minishop.model.User;

public final class HtmlPage {
    private HtmlPage() {
    }

    public static String layout(String title, String activeNav, User user, String content) {
        String navLinks = navLinks(activeNav, user);
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>%s - 青原优品</title>
                    <link rel="stylesheet" href="/styles.css">
                </head>
                <body>
                    <div class="background-rotator">
                        <span></span><span></span><span></span><span></span><span></span>
                    </div>
                    <header class="top-shell">
                        <a class="brand" href="/">青原优品</a>
                        <form class="global-search" method="get" action="/products">
                            <input name="q" placeholder="搜索青海特色商品、分类、品牌">
                            <button type="submit" aria-label="搜索">⌕</button>
                        </form>
                        <div class="account">
                            <strong>%s</strong>
                            <span>%s</span>
                            <a href="/logout">退出</a>
                        </div>
                    </header>
                    <nav class="main-nav">
                        %s
                    </nav>
                    <main class="main">
                        %s
                    </main>
                    %s
                    <script>
                    (function () {
                        var savedTheme = localStorage.getItem("qy-theme") || "light";
                        var savedFont = localStorage.getItem("qy-font") || "normal";
                        var savedStyle = localStorage.getItem("qy-style") || "plain";
                        document.documentElement.dataset.theme = savedTheme;
                        document.documentElement.dataset.font = savedFont;
                        document.documentElement.dataset.style = savedStyle;
                    })();
                    </script>
                </body>
                </html>
                """.formatted(escape(title),
                escape(user.getDisplayName()),
                escape(user.getRole().getLabel()),
                navLinks,
                content,
                footer());
    }

    public static String loginLayout(String title, String content) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>%s - 青原优品</title>
                    <link rel="stylesheet" href="/styles.css">
                </head>
                <body class="login-body">
                    <div class="background-rotator">
                        <span></span><span></span><span></span><span></span><span></span>
                    </div>
                    %s
                    %s
                </body>
                </html>
                """.formatted(escape(title), content, footer());
    }

    private static String nav(String href, String text, boolean active) {
        return "<a class=\"" + (active ? "active" : "") + "\" href=\"" + href + "\">" + escape(text) + "</a>";
    }

    private static String navLinks(String activeNav, User user) {
        StringBuilder links = new StringBuilder();
        links.append(nav("/", "首页", activeNav.equals("home")));
        links.append(nav("/products", "商品", activeNav.equals("products")));
        if (user.getRole().isAdmin()) {
            links.append(nav("/orders", "订单管理", activeNav.equals("orders")));
            links.append(nav("/marketing", "营销管理", activeNav.equals("marketing")));
            links.append(nav("/ai", "AI分析", activeNav.equals("ai")));
            links.append(nav("/users", "账户管理", activeNav.equals("users")));
        }
        links.append(nav("/settings", "系统设置", activeNav.equals("settings")));
        return links.toString();
    }

    private static String footer() {
        return """
                <footer class="site-footer">
                    <span>平台名称：青原优品</span>
                    <span>制作人：甘钕宏 240203010139</span>
                    <span>张育玮 240907011108</span>
                    <span>冉铠源 240809010115</span>
                </footer>
                """;
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static String attr(String value) {
        return escape(value);
    }

    public static String money(double value) {
        return String.format("￥%.2f", value);
    }

    public static String nl2br(String value) {
        return escape(value).replace("\n", "<br>");
    }
}
