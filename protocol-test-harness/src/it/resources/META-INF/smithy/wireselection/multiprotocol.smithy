$version: "2.0"

namespace smithy.test.wireselection

use aws.protocols#restJson1
use smithy.protocols#rpcv2Cbor
use smithy.protocols#rpcv2Json

/// A service supporting restJson1, rpcv2Cbor, and rpcv2Json on a
/// single operation. Used by the wire-protocol-selection compliance
/// tests to exercise §Server protocol selection from
/// https://smithy.io/2.0/guides/wire-protocol-selection.html
///
/// Identification characteristics differ across protocols:
///   - restJson1: @http binding (POST /echo)
///   - rpcv2Cbor: path /service/<Name>/operation/<Op> + smithy-protocol: rpc-v2-cbor
///   - rpcv2Json: same path as rpcv2Cbor + smithy-protocol: rpc-v2-json
///
/// rpcv2Cbor and rpcv2Json share the same URI shape; they MUST be
/// distinguished by the smithy-protocol header. This is the
/// canonical test for header-based protocol disambiguation on a
/// shared path — the bridge's bucket-dispatch logic exists
/// specifically for this case.
@restJson1
@rpcv2Cbor
@rpcv2Json
service MultiProtocol {
    version: "2024-01-01"
    operations: [Echo]
}

/// A no-op operation. Tests assert on whether the operation was
/// invoked, not on input/output content.
@http(method: "POST", uri: "/echo")
operation Echo {
    input := {}
    output := {}
}
