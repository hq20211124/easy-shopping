import SwiftUI
import WebKit

/// 图文详情：WKWebView 渲染服务端富文本（plans/ios-frontend 5.2 —— 图文混排交给 H5）。
/// P0 固定高度、内部滚动；高度自适应与 JSBridge 留待 P1。
struct RichTextView: UIViewRepresentable {
    let html: String
    var height: CGFloat = 480

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.contentInset = .zero
        webView.navigationDelegate = context.coordinator
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        guard !context.coordinator.didLoad else { return }
        context.coordinator.didLoad = true
        let page = """
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
          html, body { margin: 0; padding: 0; background: transparent; color: #111;
                       font: 15px/1.65 -apple-system, "PingFang SC", sans-serif; word-break: break-word; }
          img, video { max-width: 100%; height: auto; display: block; }
          p { margin: 0 0 10px; }
        </style>
        \(html)
        """
        webView.loadHTMLString(page, baseURL: nil)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var didLoad = false
    }
}
