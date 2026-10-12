import SwiftUI

/// SKU 规格矩阵：维度 × 选项，按「其它维度已选」动态禁用不可组合项 + 数量步进。
struct SkuMatrixView: View {
    @ObservedObject var vm: ProductDetailViewModel

    private let columns = [
        GridItem(.adaptive(minimum: 64), spacing: 8, alignment: .leading)
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if vm.specGroups.isEmpty && vm.skus.isEmpty {
                Text("暂无可售规格")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }

            ForEach(vm.specGroups) { group in
                VStack(alignment: .leading, spacing: 8) {
                    Text(group.name)
                        .font(.subheadline)
                        .foregroundColor(.secondary)

                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(group.values ?? [], id: \.self) { value in
                            optionButton(group: group.name, value: value)
                        }
                    }
                }
            }

            if !vm.specGroups.isEmpty {
                Text("已选：\(vm.selectedText.isEmpty ? "请选择规格" : vm.selectedText)")
                    .font(.caption)
                    .foregroundColor(vm.selectedText.isEmpty ? .secondary : .primary)
            }

            HStack {
                Text("数量")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                Spacer()
                QuantityStepper(quantity: $vm.quantity, range: 1...99)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card)
        .cornerRadius(AppTheme.cornerRadius)
    }

    private func optionButton(group: String, value: String) -> some View {
        let selected = vm.selectedSpecs[group] == value
        let disabled = vm.isOptionDisabled(group: group, value: value)
        return Button {
            guard !disabled else { return }
            vm.toggle(group: group, value: value)
        } label: {
            Text(value)
                .font(.caption)
                .lineLimit(1)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .frame(maxWidth: .infinity)
                .background(selected ? AppTheme.primary : (disabled ? Color.gray.opacity(0.12) : AppTheme.card))
                .foregroundColor(selected ? .white : (disabled ? .secondary : .primary))
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(selected ? Color.clear : Color.gray.opacity(0.35), lineWidth: 1)
                )
                .cornerRadius(6)
                .opacity(disabled ? 0.55 : 1)
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }
}
