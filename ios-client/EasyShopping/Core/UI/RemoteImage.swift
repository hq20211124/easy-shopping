import SwiftUI

/// 网络图：统一用 AsyncImage（零三方依赖；磁盘缓存/预取等增强留待 P1 引入图片库）。
struct RemoteImage: View {
    let urlString: String?

    var body: some View {
        AsyncImage(url: RemoteURL.make(urlString)) { phase in
            switch phase {
            case .success(let image):
                image
                    .resizable()
                    .scaledToFill()
            case .failure:
                placeholder
            case .empty:
                ZStack {
                    placeholder
                    ProgressView()
                }
            @unknown default:
                placeholder
            }
        }
    }

    private var placeholder: some View {
        ZStack {
            Color.gray.opacity(0.15)
            Image(systemName: "photo")
                .foregroundColor(.secondary)
        }
    }
}
