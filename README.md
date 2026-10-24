# Sell

DonutSMP-style `/sell`.

- Big chest menu: drop items into the open slots
- Lime pane (bottom-right) shows "Sell Items?" and the payout, e.g. `$1.2k`, `$100.4k`, `$1.1m`
- Payout is rounded: thousands to the nearest 100, millions to the nearest 100k
- Click the lime pane to sell; unsellable items are returned
- Closing the menu returns anything you didn't sell
- EVERY item in Minecraft is sellable: ~400 hand-priced items, and anything else gets an automatic price (spawn eggs, discs, wool, wood, tools, armor, potions, everything)
- Enchanted items add value per enchant level, enchanted books too; renamed items sell; armor trims add bonuses; special rules give premiums for god gear
- Shulker boxes/bundles with items inside are refused so nothing is destroyed
- `/sell worth` tells you what the item in your hand is worth, or exactly why it can't be sold

Requires Vault + an economy plugin. `/sell reload` (needs `sell.admin`) reloads prices.

## Build
`mvn package` -> `target/Sell.jar` (Java 21). GitHub Actions builds on every push; tag `v*` to publish a release.
