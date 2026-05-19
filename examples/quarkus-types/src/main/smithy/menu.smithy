$version: "2"

namespace com.example

/// A coffee shop menu — the data shape this example exposes.
structure Menu {
    @required
    items: MenuItems
}

list MenuItems {
    member: MenuItem
}

structure MenuItem {
    @required
    name: String

    /// Price in cents.
    @required
    priceCents: Integer

    description: String
}
