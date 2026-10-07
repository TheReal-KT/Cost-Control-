# Building polished Android and iOS interfaces

Research date: 1 October 2026. Researched with the Exa Search skill: nine searches across three themes (design practice, icons, and implementation), returning 45 results and 43 unique search URLs. Thirteen selected source pages were fetched. Search results were screened for relevance; the explanation relies on first-party documentation and Shopify's engineering account. The Shopify article is from 2020 and is used as a historical implementation example, rather than evidence about current package versions.

## The central idea

A programming language does not determine how an interface looks. A design system defines its visual rules; the UI framework implements those rules. Separate Kotlin and Swift implementations can follow the same specification, or a cross-platform framework can share the implementation. Android's Compose documentation explicitly supports extending Material or building a custom design system. [Android custom design systems](https://developer.android.com/develop/ui/compose/designsystems/custom)

Shopify's Shop redesign illustrates the process: designers defined the visual system while engineers developed Restyle to express consistent spacing, semantic colours, typography and reusable components. Restyle is a TypeScript library for constructing themed React Native UI components. [Shopify engineering](https://shopify.engineering/5-ways-to-improve-your-react-native-styling-workflow), [Restyle documentation](https://shopify.github.io/restyle/)

## A practical design workflow

My suggested starting workflow:

1. Sketch one representative screen in a design tool such as Figma.
2. Define a small set of named values: screen padding, spacing steps, text styles, corner shapes and colours for purposes such as background, primary action and error.
3. Select one icon family and establish its size and weight rules.
4. Implement reusable buttons, cards, inputs and navigation items.
5. Construct other screens from those components.
6. Check real content, long labels, loading and error states, dark mode, larger text and different screen sizes.

These named design values are often called **design tokens**. For example, a specification might say that cards use 16 logical units of padding and a named body text style. Kotlin, Swift and TypeScript can each express those same rules; their files do not need identical syntax.

For Android, Compose previews support different rendering configurations. Adaptive layouts account for available window space, and accessibility includes text scaling and the meaning of controls for assistive technologies. These are part of visual quality, not an optional final polish. [Compose previews](https://developer.android.com/develop/ui/compose/tooling/previews), [adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps), [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility)

## How the platforms share a design

| Approach | What is shared | How the interface appears |
| --- | --- | --- |
| Separate native apps | Design specification, icon artwork and optionally generated tokens | Kotlin/Compose and Swift/SwiftUI separately implement the specification. |
| React Native | TypeScript/JavaScript logic and React components | The renderer creates platform host views; deliberate styling and platform adjustments maintain consistency. |
| Flutter | Dart logic and widget code | Flutter's rendering system draws the application UI across platforms. |
| Kotlin Multiplatform with Compose Multiplatform | Kotlin logic and composable UI | Shared UI is hosted by platform-specific app entry points. |

React Native offers platform detection and separate platform files when behaviour needs to differ. Flutter includes platform integration around its shared UI. Kotlin Multiplatform can share only business logic, leaving native UIs separate, or share UI through Compose Multiplatform. Jetpack Compose in an ordinary Android project does not automatically create an iOS app. [React Native renderer](https://reactnative.dev/architecture/render-pipeline), [platform-specific code](https://reactnative.dev/docs/platform-specific-code), [Flutter architecture](https://docs.flutter.dev/resources/architectural-overview), [Kotlin sharing options](https://kotlinlang.org/docs/multiplatform/kotlin-multiplatform-react-native.html)

Aim for consistent branding while preserving useful platform behaviour. System keyboards and text-selection menus may differ. Even a shared font can rasterize differently, so exact pixel equality is not guaranteed. [Compose platform differences](https://kotlinlang.org/docs/multiplatform/compose-platform-specifics.html)

## Choosing icons

For a restrained outline style, my starting recommendation is **Lucide**. It provides individual SVG artwork independently of framework packages. Its ISC licence, and the MIT licence for inherited Feather icons, require retaining the relevant notices. [Lucide static assets](https://lucide.dev/guide/static/), [Lucide licence](https://lucide.dev/license)

Apple's **SF Symbols** integrates closely with Apple's system font and supplies multiple weights and rendering variants. It has Apple-specific terms and restrictions; do not assume exporting artwork grants permission for every platform. For a shared Android/iOS asset set, select a library whose licence permits the intended distribution. [Apple SF Symbols guidance](https://developer.apple.com/design/human-interface-guidelines/sf-symbols)

**Material Symbols** is another option. Current Android guidance recommends downloading its Android XML assets rather than depending on the older Material Icons artifact. [Android icon guidance](https://developer.android.com/develop/ui/compose/graphics/images/material)

Choose consistent visual weight, optical size and alignment. An icon should remain recognizable at its actual on-screen size. Use the same asset family for shared branded controls, or map semantic actions such as Search to platform-specific symbols when a more native appearance is intended.

## Android Studio compared with TypeScript

Android Studio is an IDE, similar in role to VS Code. Gradle resolves dependencies and builds the Android project. A dependency can come from a configured remote repository, and Gradle resolves its transitive dependencies. Android documentation recommends a version catalog for centralising dependency declarations. [Add build dependencies](https://developer.android.com/build/dependencies)

| Familiar concept | Native Android counterpart |
| --- | --- |
| Dependency declarations in package.json | Module build.gradle.kts plus optional gradle/libs.versions.toml |
| npm/pnpm resolving packages | Gradle resolving libraries during sync/build |
| React function component | Compose function annotated with @Composable |
| ThemeProvider | MaterialTheme or an application theme composable |
| Component styles | Modifier, component parameters and theme values |
| SVG icon component | Vector drawable resource loaded into Icon |

These are conceptual comparisons, not interchangeable file formats. An npm React package cannot be imported directly into native Kotlin code. Shared SVG artwork can be reused even when the language-specific wrappers differ.

For example, inside an **existing Compose-enabled Android project**, with Google's Maven repository configured, dependency declarations can look like:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
}
```

The BOM version above was shown in the fetched official documentation; it is an example, not a promise that it will remain the newest version. A BOM is a compatibility list for Compose library versions. It does not install every Compose library and does not manage the Kotlin/Compose compiler configuration. If the project already declares these dependencies through a version catalog, update those declarations rather than duplicating them. Sync Gradle, then import and use the library's components. [Compose BOM](https://developer.android.com/develop/ui/compose/bom)

Installing a library makes its components available. To change the whole application's style, define a theme and ensure the components read it. MaterialTheme supports colours, typography and shapes. Dynamic wallpaper colours are optional; use your own palette when matching brand colours matters. [Material 3 theming](https://developer.android.com/develop/ui/compose/designsystems/material3)

## Importing a shared SVG icon into Android

1. Obtain the icon's SVG from the selected library.
2. In Android Studio, right-click the drawable resources folder and choose New > Vector Asset, then choose a local SVG file.
3. Inspect the conversion preview and save the generated drawable, for example ic_search.xml.
4. Display it through Compose's Icon component.

Android Studio supports a subset of SVG features and converts the file to VectorDrawable XML. Some assets need simplification or conversion fixes, so check the resulting appearance. [Vector Asset Studio](https://developer.android.com/studio/write/vector-asset-studio)

```kotlin
// Inside a composable, after importing the SVG as ic_search.xml:
Icon(
    painter = painterResource(R.drawable.ic_search),
    contentDescription = null,
    modifier = Modifier.size(24.dp)
)
```

This fragment assumes the corresponding Compose imports and generated resource exist. It is a decorative icon example: an icon-only interactive control needs a localized accessible action label. An icon adjacent to an already-labelled control can avoid repeating that label. [Android Icon usage](https://developer.android.com/develop/ui/compose/graphics/images/material), [accessibility](https://developer.android.com/develop/ui/compose/accessibility)

## Choosing your next step

Given your TypeScript background, my default recommendation for a new Android/iOS project is to evaluate **React Native with Expo** first. Expo supports TypeScript and uses the native Android toolchain for local Android builds. Android Studio supplies Android SDK/emulator tools and can inspect the native project; it does not require moving the shared UI into Kotlin. Local iOS compilation uses Xcode on macOS; Expo also offers cloud builds. [Expo documentation](https://docs.expo.dev/), [local builds](https://docs.expo.dev/guides/local-app-development/), [development builds](https://docs.expo.dev/develop/development-builds/introduction/)

If your purpose is learning native Android development or improving an existing Kotlin application, use **Jetpack Compose**, a deliberately configured theme, reusable components and a consistent icon family. If sharing Kotlin UI becomes an actual requirement, evaluate Compose Multiplatform rather than assuming an Android-only project is already multiplatform.

The code here is explanatory and was not compiled against an application. No application code or dependencies were changed.
