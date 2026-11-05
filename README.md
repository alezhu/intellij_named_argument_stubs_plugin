# Kotlin named arguments stubs plugin for IntelliJ IDEA and Android Studio
<!-- Plugin description -->
Add or create named arguments for call function and class constructors in Kotlin code.
When several overloads fit the call, ask which one it means before writing the stubs.
<!-- Plugin description end -->

[Install from Jetbrains Plugin Marketplace](https://plugins.jetbrains.com/plugin/20797-kotlin-named-argument-stubs/)
or
[Download release](https://github.com/alezhu/intellij_named_argument_stubs_plugin/releases)

The intention rewrites the call's argument list: positional arguments keep their order but get their
parameter names, the parameters the call leaves out are stubbed as `name = <value>`, and a trailing
lambda stays outside the rewritten list.

When several overloads fit the call, the plugin asks which one it means instead of generating names
from the first one. When exactly one fits, the intention popup previews the expansion.

![Screenshot](https://user-images.githubusercontent.com/1639172/212131142-fa427eb8-9cb8-46fb-87fb-68e9eb9ec43e.png)
