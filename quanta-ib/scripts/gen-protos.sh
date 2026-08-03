#!/usr/bin/env bash
# Copy curated IB .proto files from ib-tws, strip proto3 optional, regenerate Clojure bindings.
# Full Order.proto exceeds the JVM 64KB method limit under protoc-gen-clojure, so we overlay
# a slim Order (same field numbers) covering place/cancel/open-order fields we use.
#
# Requires on PATH:
#   protoc
#   protoc-gen-clojure  (e.g. release binary in /tmp; see protojure/protoc-plugin)
# Example:
#   PATH=/tmp:$PATH nix-shell -p protobuf --run 'bash scripts/gen-protos.sh'
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IB_PROTO="${IB_PROTO:-/home/florian/repo/clojure-quant/ib-tws/IBJts/source/proto}"
DEST="$ROOT/resources/proto"
mkdir -p "$DEST" "$ROOT/src"
command -v protoc >/dev/null || { echo "protoc not on PATH" >&2; exit 1; }
command -v protoc-gen-clojure >/dev/null || { echo "protoc-gen-clojure not on PATH" >&2; exit 1; }

# Quote + trade messages and their dependencies.
PROTOS=(
  CancelMarketData.proto
  CancelOrderRequest.proto
  ComboLeg.proto
  Contract.proto
  DeltaNeutralContract.proto
  ErrorMessage.proto
  Execution.proto
  ExecutionDetails.proto
  ManagedAccounts.proto
  MarketDataRequest.proto
  MarketDataType.proto
  MarketDataTypeRequest.proto
  NextValidId.proto
  OpenOrder.proto
  OrderCancel.proto
  OrderStatus.proto
  PlaceOrderRequest.proto
  StartApiRequest.proto
  TickGeneric.proto
  TickPrice.proto
  TickReqParams.proto
  TickSize.proto
  TickString.proto
)

rm -f "$DEST"/*.proto
for name in "${PROTOS[@]}"; do
  sed 's/optional //g' "$IB_PROTO/$name" > "$DEST/$name"
done

# Slim OpenOrder: skip OrderState (pulls OrderAllocation) — unused by blotter messaging.
cat > "$DEST/OpenOrder.proto" <<'EOF'
syntax = "proto3";

package protobuf;

import "Contract.proto";
import "Order.proto";

option java_outer_classname = "OpenOrderProto";
option java_package = "com.ib.client.protobuf";
option csharp_namespace = "IBApi.protobuf";

message OpenOrder {
    int32 orderId = 1;
    Contract contract = 2;
    Order order = 3;
}
EOF

# Slim Order: keep IB field numbers for wire compatibility.
cat > "$DEST/Order.proto" <<'EOF'
syntax = "proto3";

package protobuf;

option java_outer_classname = "OrderProto";
option java_package = "com.ib.client.protobuf";
option csharp_namespace = "IBApi.protobuf";

// Curated subset of IB Order.proto (field numbers preserved).
// Full Order is too large for protoc-gen-clojure (JVM method size limit).
message Order {
    string action = 5;
    string totalQuantity = 6;
    string orderType = 8;
    double lmtPrice = 9;
    double auxPrice = 10;
    string tif = 11;
    string orderRef = 28;
    bool transmit = 66;
}
EOF

# PlaceOrderRequest without AttachedOrders keeps the dependency graph small.
cat > "$DEST/PlaceOrderRequest.proto" <<'EOF'
syntax = "proto3";

package protobuf;

import "Contract.proto";
import "Order.proto";

option java_outer_classname = "PlaceOrderRequestProto";
option java_package = "com.ib.client.protobuf";
option csharp_namespace = "IBApi.protobuf";

message PlaceOrderRequest {
    int32 orderId = 1;
    Contract contract = 2;
    Order order = 3;
}
EOF

protoc --proto_path="$DEST" --clojure_out="$ROOT/src" "$DEST"/*.proto
echo "Vendored $(ls "$DEST"/*.proto | wc -l) protos; generated into $ROOT/src"
