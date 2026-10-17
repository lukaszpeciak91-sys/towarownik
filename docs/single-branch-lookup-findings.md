# Single-branch lookup findings

Source-only inspection of current `main`, verified through GitHub and the matching local checkout: **`dfee248a86049a10d2d70add2bafd7189517c087`**. All references below are pinned to that commit. No tests, builds, live API probes or production changes were performed for this focused report. No PR is requested or created for this report.

## 1. Can ordinary lookup fetch fresh stock in one requested location different from WorkingProfile?

**Yes, for both providers at the provider/repository layer. WorkingProfile does not prevent it.**

- **OBI:** `ObiProductProvider.lookup(ref, branchId)` passes the product ID and requested branch to `ProductLookupRepository.lookupObik(obik, storeNumber)`. That repository validates the OBIK/store and fetches the product for that store. `ObiHttpClient.fetchProduct` supplies `storeNumber` to the store-selection URL. The ordinary advisor tool, `FindObiProductsTool.executeBlocking`, already bypasses textual search for an exact OBIK and calls `verifyExactObik` with `arguments.storeNumber`. [OBI provider][obi-provider], [repository][obi-lookup], [HTTP][obi-http], [ordinary OBI tool][obi-tool].
- **KWANT:** `KwantProductProvider.lookup(ref, branchId)` resolves the requested branch against the provider directory, then calls `lookupWithBranch`. It fetches and verifies the exact product and requests fresh selected-branch stock through `/api/front/products/ID/current?depstock=BRANCH`. `parseSelectedBranchDetails` requires the expected product ID and department ID. `openLookupScope(branchId)` exposes the same exact lookup for a validated branch. [KWANT lookup][kwant], [current request][current-http], [stock validation][current-parser].

Android's existing `AdvisorController.runTurn.authorizeToolArguments` can independently resolve an explicit USER location against the directory and rewrite the ordinary tool's branch for that turn. `forResolvedBranch` copies arguments; it does not change the profile. [Authorization][authorization], [argument copy][copy].

“Fresh” here means the implementation makes a new provider retrieval. Source alone does not establish live success, quantity, latency or upstream freshness guarantees. KWANT returns null stock if its current-stock read fails; it does not reuse the HTML quantity. [KWANT lookup][kwant].

## 2. Which functions/contracts prevent this, if any?

**No provider contract prevents exact lookup in another validated branch. There are advisor execution gaps:**

| Surface | Verified limitation |
| --- | --- |
| `ProductProvider.lookup(ProductRef, BranchId)` / `openLookupScope` | Already accept exact identity and explicit target branch. No provider API extension is required. [Contract][provider-contract] |
| `FindProviderProductsTool.executeBlocking` | Opens the requested branch scope but always calls `provider.search(query, limit)` before exact-verifying candidates. It has no Android-only trusted-ref execution entry point. Thus the ordinary KWANT model tool cannot currently refresh a known ref directly without rediscovery. [Provider tool][provider-tool] |
| `AdvisorController` location-request dispatch | Every authorized `Inventory` goes to `executeLocationsTool`; it does not switch retrieval when `authority.branches` contains one branch. [Dispatch][dispatch] |
| `AdvisorInventoryAuthorizer.resolve` / `resolveTrustedProduct` | Require independently trusted identity, valid directory/scope and acceptable USER/reference evidence. A model product ID is checked against independently selected identity. Requests failing these gates never reach either proposed singleton lookup or inventory. Keep these security checks. [Authorizer][authorizer] |

An article code or EAN is not automatically a provider-owned internal product ID. For a new or ambiguous identity, ordinary search followed by exact verification is still required. A direct-ref shortcut must take Android-authorized identity, never reinterpret arbitrary model query text as a KWANT internal ID. [Provider tool][provider-tool], [identity selection][authorizer].

## 3. Why does a single-branch stock follow-up use `find_product_locations`?

There are two separate decisions:

1. **Model-facing policy groups other-location checks into inventory.** Worker `LOCATIONS_INSTRUCTIONS` says: “Use find_product_locations ONLY for explicit other-location stock requests about one exact trusted product.” It also describes a USER-named KWANT branch as restricting that tool's output to the branch. `LOCATIONS_TOOL` is described as checking other-location stock; neither definition reserves it for multiple locations. This steers tool choice, but does not prove what a live model chose in a particular incident. [Worker policy][policy], [tool definition][location-schema].
2. **Android dispatch follows that selected tool without a singleton correction.** After authorization, Controller sends any `Inventory` to `AdvisorLocationsTool.execute(authority)`. That executor uses the locations service even for one branch: KWANT reads `/departments?extended=1`, then projects authorized rows; OBI uses its inventory path. It does not call ordinary `ProductProvider.lookup` for a singleton. [Controller][dispatch], [executor][executor], [KWANT inventory request][departments].

Therefore, a one-branch output restriction still uses the inventory retrieval path. This is a verified routing behavior, not proof of the reported UNKNOWN root cause. The endpoint distinction alone cannot identify that failure.

## 4. What is the minimum code change to reuse ordinary lookup for one validated target?

**Add one singleton retrieval branch after existing typed authorization, before the inventory service call.** The smallest practical location is `AdvisorLocationsTool.execute(authority)`, which already receives the trusted product, validated branch list, directory and `fullNetwork` flag. Controller can keep its current tool dispatch and continuation. [Typed authority][authority-type], [executor][executor].

Recommended implementation, not implemented here:

1. When `!authority.fullNetwork && authority.branches.size == 1`, construct the exact ref from `authority.provider` and the independently authorized product. Use the existing provider registry and call that provider's ordinary `lookup(ref, targetBranch)` off the UI thread. Check the returned product/provider/branch against the authorized inputs. OBI and KWANT then use their existing exact verification and HTTP implementations.
2. Convert the fresh result to the existing one-branch `AdvisorLocationEvidence`. For known stock, report verified coverage for that branch; for failed/null stock, preserve unavailable/unknown and a null row. Never copy historical stock or central stock into the response. Preserve the current tool-call budget and cancellation behavior.
3. Otherwise keep the existing multi-location/network inventory execution. Test the explicit network flag as well as cardinality: a network request must not become ordinary lookup merely because the directory happens to contain one branch.

This requires local provider access/injection and result conversion in the existing executor, not a new HTTP implementation, provider abstraction, natural-language parser or model tool. It also avoids repeating textual search for an already trusted product. A separate overload in `FindProviderProductsTool` is unnecessary for this smallest compatibility path; it would be needed only if direct trusted-ref execution must also be exposed through that ordinary tool wrapper. [Existing provider contract][provider-contract], [ordinary wrapper limitation][provider-tool].

**Unresolved constraints:** this narrow change only handles requests that existing authorization already accepts. It does not fix unsupported Polish wording, ambiguous references or new-product discovery. Missing/invalid directories and explicit conflicting USER product/location intent must still reject or clarify. Existing location replies do not automatically create fresh ordinary product cards; the minimal path should preserve that behavior instead of promoting historical cards. Full exact KWANT lookup also fetches product HTML/branch metadata, so fewer HTTP requests or better reliability cannot be promised from source alone. [Authorizer][authorizer], [KWANT lookup][kwant], [final card selection][cards].

## 5. Can this work without Worker schema changes or saved WorkingProfile changes?

**Yes, for the minimal retrieval change above.**

- Keep the original `find_product_locations` call and answer it with its existing location-evidence schema. `validateLocationToolResult` already accepts a single checked location, verified stock or an unavailable/null result with matching coverage. Do not send a grouped `find_products` result under a location call. No new Worker tool arguments, protocol version or result fields are necessary. [Result validator][validator].
- Use the authorized branch only for the provider lookup. Controller's existing argument-copy mechanism and continuations retain the conversation profile; no profile save belongs in this path. [Argument copy][copy], [location continuation][continuation].
- If the desired change also requires the **model tool name** to be the ordinary product tool on every single-location follow-up, that is broader than reusing ordinary retrieval internally. Existing tool schemas already carry branch/query information, but ordinary KWANT execution would need the trusted-ref shortcut and shared reference authorization described above. Worker wording could then be aligned with deterministic routing; wording alone would not enforce it. [Provider tool][provider-tool], [Worker policy][policy], [ordinary authorization][authorization].

Recommendation: implement the small authorized-singleton executor path first, retain multi/network inventory and the original wire envelope, and make no claim that it diagnoses or resolves the live UNKNOWN incident without separate evidence.

[provider-contract]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/product/provider/ProductProvider.kt#L150
[obi-provider]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/product/provider/ObiProductProvider.kt#L63
[obi-lookup]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/product/ProductLookup.kt#L48
[obi-http]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/product/ObiHttpClient.kt#L60
[obi-tool]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/FindObiProductsTool.kt#L59
[kwant]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/product/provider/KwantProductProvider.kt#L364
[current-http]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/product/provider/KwantProductProvider.kt#L144
[current-parser]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/product/provider/KwantProductProvider.kt#L661
[authorization]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/AdvisorController.kt#L184
[copy]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/AdvisorController.kt#L1045
[provider-tool]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/FindProviderProductsTool.kt#L52
[dispatch]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/AdvisorController.kt#L509
[authorizer]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/AdvisorInventoryAuthorization.kt#L80
[policy]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/proxy/src/config.ts#L52
[location-schema]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/proxy/src/config.ts#L424
[executor]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/AdvisorLocationsTool.kt#L63
[departments]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/product/provider/KwantProductLocationsAdapter.kt#L57
[authority-type]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/AdvisorInventoryAuthorization.kt#L15
[cards]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/AdvisorController.kt#L423
[validator]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/proxy/src/validation.ts#L366
[continuation]: https://github.com/lukaszpeciak91-sys/towarownik/blob/dfee248a86049a10d2d70add2bafd7189517c087/app/src/main/java/pl/lukaszpeciak/towarownik/agent/AdvisorController.kt#L559
