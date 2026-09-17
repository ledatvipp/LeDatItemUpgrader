#!/usr/bin/env python3
"""Supplemental YAML/resource consistency check. Requires PyYAML; NOT a SnakeYAML/Paper integration test."""
from pathlib import Path
import re
import yaml

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'paper/src/main/resources'
checks = 0

def require(condition, message):
    global checks
    checks += 1
    if not condition:
        raise AssertionError(message)

class UniqueSafeLoader(yaml.SafeLoader):
    pass

def mapping(loader, node, deep=False):
    loader.flatten_mapping(node)
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f'duplicate YAML key: {key!r}')
        result[key] = loader.construct_object(value_node, deep=deep)
    return result
UniqueSafeLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)

files = {}
for path in sorted(RES.rglob('*.yml')):
    with path.open(encoding='utf-8') as stream:
        loaded = yaml.load(stream, Loader=UniqueSafeLoader)
    require(isinstance(loaded, dict), f'{path}: root mapping required')
    files[path.relative_to(RES).as_posix()] = loaded

require(files['config.yml']['features']['upgrades-enabled'] is False, 'transaction must remain disabled')
require(files['config.yml']['features']['packet-renderer-enabled'] is False, 'packet renderer must remain disabled')
require(files['config.yml']['storage']['mode'] == 'PLATFORM_SHARED', 'unexpected storage mode')
for name, data in files.items():
    if 'config-version' in data:
        require(data['config-version'] == 1, f'{name}: version')
require(files['messages.yml']['messages-version'] == 1, 'message version')
for key, value in files['messages.yml'].items():
    if key != 'messages-version':
        require(isinstance(value, str), f'{key}: string expected')

source = (ROOT / 'core/src/main/java/vn/ledat/itemupgrader/value/ValueResult.java').read_text(encoding='utf-8')
statuses = re.search(r'enum Status\s*\{([^}]+)\}', source, re.S).group(1)
for status in statuses.split(','):
    status = status.strip()
    if status != 'AVAILABLE':
        key = 'value-' + status.lower().replace('_', '-')
        require(key in files['messages.yml'], f'missing result message {key}')

for entry in files['upgrades/values.yml']['manual']:
    require(isinstance(entry['value'], str), f"decimal must be quoted: {entry['item']}")
menu = files['menus/upgrader.yml']
require(len(menu['matrix']) == 6, 'default menu should have 6 rows')
require(all(len(row) == 9 for row in menu['matrix']), 'each row must have 9 slots')
source_count = 0
for row in menu['matrix']:
    for symbol in row:
        require(symbol in menu['symbols'], f'undefined symbol: {symbol}')
        source_count += menu['symbols'][symbol]['role'] == 'SOURCE_INPUT'
require(source_count == 1, 'exactly one source reference selector')
for element in menu['symbols'].values():
    require('name' in element and 'lore' in element, 'GUI text must be configured')
    if element['action'] in ('SELECT_PROFILE', 'TOGGLE_BOOST'):
        require(bool(element.get('argument')), 'selection needs argument')

plugin = files['plugin.yml']
require(plugin['depend'] == ['LeDatPlatform'], 'hard dependency mismatch')
require('folia-supported' not in plugin, 'Folia not verified')
main = ROOT / 'paper/src/main/java' / (plugin['main'].replace('.', '/') + '.java')
require(main.is_file(), 'main class source absent')
require((ROOT / 'build.gradle').is_file() and (ROOT / 'settings.gradle').is_file(), 'Gradle descriptors missing')
require(not list(ROOT.glob('**/src/main/java/vn/ledat/platform/**/*.java')), 'must not ship fake Platform API')

# Phase 2: independently check bundled schema/cross references. Does not emulate runtime providers.
catalog = files['upgrades/catalog.yml']
paths = files['upgrades/paths.yml']['paths']
options = catalog['settings']
from decimal import Decimal
for key in ['minimum-ratio', 'maximum-ratio', 'preferred-ratio']:
    require(isinstance(options[key], str), f'quoted catalog decimal: {key}')
require(Decimal('1') <= Decimal(options['minimum-ratio']) <= Decimal(options['preferred-ratio']) <= Decimal(options['maximum-ratio']), 'catalog ratio ordering')
require(1 <= options['page-size'] <= 45, 'catalog page-size range')
targets = catalog['targets']
by_id = {t['id']: t for t in targets}
require(len(by_id) == len(targets), 'duplicate target id')
require(len({(t['item'], t['amount']) for t in targets}) == len(targets), 'duplicate target item/quantity')
require(len(targets) <= options['maximum-targets'], 'target budget')
for target in targets:
    require(re.fullmatch(r'[a-z0-9][a-z0-9_.-]{0,63}', target['id']) is not None, 'target id validation')
    require(1 <= target['amount'] <= 64, 'target amount')
    require(isinstance(target['name'], str) and bool(target['name']), 'configured target text')
    for field in ['tags', 'conditions', 'allowed-sources']:
        require(isinstance(target[field], list) and len(target[field]) == len(set(target[field])), f'target {field} duplicates')
priority_keys = set()
require(len({p['id'] for p in paths}) == len(paths), 'duplicate path id')
for path in paths:
    require(path['mode'] in ('LOCKED', 'OPEN'), 'path mode')
    require(path['sources'] and path['targets'], 'nonempty path selectors')
    require(len(path['targets']) == len(set(path['targets'])), 'duplicate path target')
    require(set(path['targets']).issubset(by_id), 'dangling path target')
    if path['enabled']:
        for source in path['sources']:
            key = (source, path['priority'])
            require(key not in priority_keys, 'ambiguous path priority')
            priority_keys.add(key)
require('ledatitemupgrader.admin.catalog' in plugin['permissions'], 'catalog permission descriptor')
for sub in ('catalog', 'recommend', 'paths'):
    require(f'help-{sub}' in files['messages.yml'], f'help message for {sub}')
# Every literal send key is present; dynamic status families are separately enumerated below.
for java in (ROOT/'paper/src/main/java').rglob('*.java'):
    text = java.read_text(encoding='utf-8')
    for key in re.findall(r'messages\.send\([^,]+,\s*"([a-z][a-z0-9-]*)"', text):
        if not key.endswith('-'):
            require(key in files['messages.yml'], f'{java.name}: message key {key}')
for key in ('busy', 'stale', 'timeout', 'failed'):
    require(f'catalog-build-{key}' in files['messages.yml'], f'build failure message: {key}')
for key in ('path-denied', 'path-required', 'no-targets', 'page-out-of-range'):
    require(f'catalog-{key}' in files['messages.yml'], f'query status message: {key}')
# Check exact fallback-generator input format without pretending to run Gradle.
message_text = (RES/'messages.yml').read_text(encoding='utf-8')
fallback_keys = re.findall(r"^([a-z][a-z0-9-]*): '((?:[^']|'')*)'$", message_text, re.M)
require({k for k,_ in fallback_keys} == set(files['messages.yml']) - {'messages-version'}, 'fallback generator must capture every message')
# Phase 3: supplemental checks on bundled declarative rules. The Java loader itself still needs real dependencies.
chance = files['upgrades/chance.yml']
profiles = files['upgrades/profiles.yml']['profiles']
boosts = files['upgrades/boosts.yml']['boosts']
conditions = files['upgrades/conditions.yml']['conditions']
formulas = chance['formulas']
bonuses = chance['permission-bonuses']
profile_map = {entry['id']: entry for entry in profiles}
formula_map = {entry['id']: entry for entry in formulas}
condition_ids = {entry['id'] for entry in conditions}
for label, rows in [('profiles', profiles), ('boosts', boosts), ('formulas', formulas), ('conditions', conditions), ('bonuses', bonuses)]:
    require(len(rows) == len({row['id'] for row in rows}), f'phase3 duplicate {label} id')
    for row in rows:
        require(re.fullmatch(r'[a-z0-9][a-z0-9_.-]{0,63}', row['id']) is not None, f'phase3 invalid {label} id')
def dec(raw, label):
    require(isinstance(raw, str) and re.fullmatch(r'(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,8})?', raw) is not None, f'{label}: quoted decimal')
    return Decimal(raw)
minimum = dec(chance['minimum-percent'], 'minimum-percent')
maximum = dec(chance['maximum-percent'], 'maximum-percent')
require(0 <= minimum <= maximum <= 100, 'phase3 bounds')
require(0 <= chance['maximum-selected-boosts'] <= 8, 'boost count budget')
require(1 <= chance['quote-lifetime-seconds'] <= 300, 'quote TTL bounds')
require(chance['default-profile'] in profile_map and profile_map[chance['default-profile']]['enabled'], 'active default profile')
require(chance['allow-free-protection'] is False, 'free protection default must remain off')
for formula in formulas:
    kind = formula['type']
    require(kind in ('RATIO', 'POWER', 'TABLE', 'CURVE'), 'formula type')
    if kind in ('RATIO', 'POWER'):
        require(0 < dec(formula['multiplier'], 'formula multiplier') <= 10, 'formula multiplier bounds')
        if kind == 'POWER': require(1 <= formula['exponent'] <= 8, 'power exponent range')
    else:
        points = formula['points']
        require(1 <= len(points) <= 64, 'curve/table points budget')
        pairs = [(dec(pt['ratio'], 'point ratio'), dec(pt['percent'], 'point percent')) for pt in points]
        require(pairs[0][0] == 0, 'table/curve first ratio')
        require(all(0 <= ratio <= 1 and 0 <= percent <= 100 for ratio, percent in pairs), 'point range')
        require(all(a[0] < b[0] and a[1] <= b[1] for a,b in zip(pairs, pairs[1:])), 'point monotonicity')
        if kind == 'CURVE': require(pairs[-1][0] == 1, 'curve endpoint')
resources = set()
for entry in profiles + boosts:
    require(set(entry['conditions']).issubset(condition_ids), f"unknown condition for {entry['id']}")
    require(0 < dec(entry['chance-multiplier'], 'chance multiplier') <= 10, 'chance multiplier range')
    for cost in entry['costs']:
        require(cost['type'] in ('ITEM', 'CURRENCY'), 'cost type')
        require(cost['consume'] in ('ON_ATTEMPT', 'ON_SUCCESS', 'ON_FAILURE'), 'cost outcome')
        amount = dec(cost['amount'], 'cost amount')
        require(amount > 0, 'positive raw cost')
        if cost['type'] == 'CURRENCY':
            require(cost['currency'] in ('VAULT', 'PLAYERPOINTS'), 'supported currency')
            scale = Decimal('0.01') if cost['currency'] == 'VAULT' else Decimal('1')
            cap = Decimal('1000000000000') if cost['currency'] == 'VAULT' else Decimal('2147483647')
            resources.add(('CURRENCY',cost['currency']))
        else:
            require(isinstance(cost['item'],str) and ':' in cost['item'], 'canonical cost item')
            scale,cap = Decimal('1'),Decimal('65536')
            resources.add(('ITEM',cost['item']))
        require(amount == amount.quantize(scale) and amount <= cap, 'cost precision/range')
require(len(resources) <= 32, 'cost-resource capture budget')
for profile in profiles:
    require(profile['formula'] in formula_map, 'profile formula reference')
    require(profile['failure'] in ('DESTROY','KEEP'), 'only failure terms implemented in Phase3')
    require(0 < dec(profile['fee-multiplier'],'fee multiplier') <= 10, 'fee multiplier range')
    if profile['enabled'] and profile['failure'] == 'KEEP':
        require(any(cost['consume'] in ('ON_ATTEMPT','ON_FAILURE') for cost in profile['costs']), 'keep profile must have failure loss')
for boost in boosts:
    require(boost['enabled'] is False, 'bundled examples must be opt-in')
    require(set(boost['allowed-profiles']).issubset(profile_map), 'boost profile refs')
    require(boost['protection'] in ('NONE','KEEP_SOURCE_ON_FAILURE'), 'protection mode')
    require(0 <= dec(boost['bonus-percentage-points'],'boost pp') <= 100, 'boost pp range')
seen_priorities = set()
for bonus in bonuses:
    require(bool(bonus['permission']), 'bonus permission mandatory')
    require(bonus['enabled'] is False, 'bundled rank bonuses must be opt-in')
    require(set(bonus['conditions']).issubset(condition_ids), 'bonus condition reference')
    require(0 < dec(bonus['chance-multiplier'],'bonus multiplier') <= 10, 'bonus multiplier range')
    require(0 <= dec(bonus['bonus-percentage-points'],'bonus pp') <= 100, 'bonus pp range')
    if bonus['enabled']:
        key = (bonus['group'], bonus['priority'])
        require(key not in seen_priorities, 'ambiguous bonus group priority')
        seen_priorities.add(key)
for entry in targets + paths:
    require(set(entry['conditions']).issubset(condition_ids), 'catalog/path condition now requires a definition')
for override in files['upgrades/profiles.yml']['path-profile-rules']:
    require(override['path'] in {path['id'] for path in paths}, 'path profile reference')
    require(set(override['allowed-profiles']).issubset(profile_map), 'path profile allowed refs')
    require(override['default-profile'] in override['allowed-profiles'], 'path default must be allowed')
# The supplied empty condition list intentionally makes no dependency on an installed PAPI expansion.
require(len(conditions) <= 32, 'condition budget')
require(plugin.get('softdepend') == ['PlaceholderAPI'], 'PAPI must be optional, not a hard dependency')
require('ledatitemupgrader.admin.quote' in plugin['permissions'], 'quote permission')
require(plugin['permissions']['ledatitemupgrader.bonus.vip']['default'] is False, 'bonus default permission')
for sub in ('quote','profiles','boosts'):
    require(f'help-{sub}' in files['messages.yml'], f'phase3 help {sub}')
for file_name,prefix,skip in [
    ('quote/QuoteResult.java','quote-denied-',{'QUOTED'}),
    ('cost/ResourceAssessment.java','quote-resources-',set())
]:
    text = (ROOT/'core/src/main/java/vn/ledat/itemupgrader'/file_name).read_text(encoding='utf-8')
    values = re.search(r'enum Status\s*\{([^}]+)\}',text,re.S).group(1).split(',')
    for value in values:
        status = value.strip()
        if status not in skip:
            require(prefix+status.lower().replace('_','-') in files['messages.yml'], f'missing dynamic phase3 message {status}')
for key in ('quote-failure-keep','quote-failure-destroy','quote-profiles-header','quote-boosts-header'):
    require(key in files['messages.yml'], f'missing phase3 message {key}')
# This is a safety scan, not proof of absence of side effects in future integrations.
quote_sources = [ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/service'/name for name in ('QuotePreviewService.java','CostResourceCapture.java')]
for path in quote_sources:
    text=path.read_text(encoding='utf-8')
    require(not re.search(r'\.(withdraw|deposit|removeItem|addItem|dispatchCommand|setItem)\s*\(',text), f'unexpected side effect in {path.name}')
require("0.9.0-phase09a" in (ROOT/'build.gradle').read_text(), 'source version phase09a')
# Supplemental Phase-5 checks. Native YAML/registry parsing still requires Paper integration tests.
outputs = files['upgrades/outputs.yml']
require(outputs['config-version'] == 1, 'output policy config version')
policies = {p['id']: p for p in outputs['transfer-policies']}
require(len(policies) == len(outputs['transfer-policies']), 'unique transfer ids')
require(outputs['default-transfer'] in policies, 'known default transfer')
require(outputs['default-transfer'] == 'clean', 'clean default')
for policy in policies.values():
    require(policy['durability'] in ('TARGET_DEFAULT','DAMAGE_RATIO_CEIL'), 'known durability policy')
    require(type(policy['custom-name']) is bool and type(policy['repair-cost']) is bool, 'typed transfer flags')
    for enchant in policy['enchantments']:
        require(enchant['key'].startswith('minecraft:'), 'bundled vanilla enchant key')
        require(1 <= enchant['maximum-level'] <= 255, 'transfer enchant cap')
    for entry in policy['pdc']:
        require(entry['type'] in ('STRING','INTEGER','LONG','BYTE_ARRAY'), 'typed pdc entry')
losses = {p['id']: p for p in outputs['failure-policies']}
require(len(losses) == len(outputs['failure-policies']), 'unique failure ids')
for loss in losses.values():
    require(loss['type'] in ('DESTROY','KEEP','DAMAGE','DOWNGRADE'), 'supported failure handler')
    if loss['type'] == 'DAMAGE':
        require(1 <= loss['basis-points'] <= 10000, 'damage basis points')
        require(loss['on-break'] in ('DESTROY','CLAMP_ONE'), 'damage break policy')
    if loss['type'] == 'DOWNGRADE':
        require(loss['transfer'] in policies, 'downgrade transfer reference')
        require(1 <= loss['amount'] <= 64, 'downgrade amount bound')
require(outputs['profile-failures'] == [] and outputs['path-transfers'] == [], 'new examples not enabled implicitly')
for key in ('quote-failure-damage','quote-failure-downgrade','quote-output-policy','quote-damage-detail','quote-downgrade-detail'):
    require(key in files['messages.yml'], f'missing phase5 message {key}')
require('upgrades/outputs.yml' in (ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/platform/PlatformAccess.java').read_text(), 'new bundled config installed')


# Phase 6 supplemental only: shipped assets and architecture text, NOT native inventory event testing.
settings = files['menus/settings.yml']
require(settings['source-mode'] == 'REFERENCE_ONLY', 'GUI must not take custody')
require(settings['close-behavior'] == 'DISCARD_SELECTION', 'close never gives back a phantom source')
require(type(settings['enabled']) is bool, 'GUI enabled boolean')
require(1 <= settings['maximum-sessions'] <= 256, 'bounded sessions')
require(30 <= settings['idle-timeout-seconds'] <= 1800, 'bounded session lifetime')
require(1 <= settings['request-timeout-seconds'] <= 30, 'bounded preview lifetime')
require(100 <= settings['click-cooldown-ms'] <= 3000, 'bounded click cooldown')
require(250 <= settings['open-cooldown-ms'] <= 10000, 'bounded open cooldown')
require(1024 <= settings['maximum-icon-bytes'] <= 65536, 'bounded native preview bytes')
require(set(settings['sounds']).issubset({'open','click','error','close'}), 'known sound cues')
for cue in settings['sounds'].values():
    require(not cue['key'] or re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+', cue['key']), 'sound key syntax, registry check needs runtime')
    require(Decimal(cue['volume']).is_finite() and Decimal('0') <= Decimal(cue['volume']) <= Decimal('2'), 'sound volume bounds')
    require(Decimal(cue['pitch']).is_finite() and Decimal('0.5') <= Decimal(cue['pitch']) <= Decimal('2'), 'sound pitch bounds')
model = (ROOT/'core/src/main/java/vn/ledat/itemupgrader/gui/MenuDefinition.java').read_text(encoding='utf-8')
roles = {s.strip() for s in re.search(r'enum Role\s*\{([^}]+)\}', model).group(1).split(',')}
actions = {s.strip() for s in re.search(r'enum Action\s*\{([^}]+)\}', model).group(1).split(',')}
expected_roles = {'catalog':'CATALOG_ENTRY','profiles':'PROFILE_ENTRY','boosts':'BOOST_ENTRY'}
dynamic_actions = {'CATALOG_ENTRY':'SELECT_TARGET','PROFILE_ENTRY':'SELECT_PROFILE','BOOST_ENTRY':'TOGGLE_BOOST'}
allowed_placeholders = {'chance','source_value','target_value','source','target','profile','fee','failure','source_slot','item','amount','value','id','multiplier','fee_multiplier','points','page','pages','category','sort','boosts','status','selected'}
for name in ('upgrader','catalog','profiles','boosts'):
    menu=files[f'menus/{name}.yml']
    require(1 <= len(menu['matrix']) <= 6 and all(len(row)==9 for row in menu['matrix']), f'{name} grid')
    elements=[menu['symbols'][symbol] for row in menu['matrix'] for symbol in row]
    require(sum(e['role']=='SOURCE_INPUT' for e in elements) == (1 if name=='upgrader' else 0), f'{name} source reference count')
    require(any(e['role']=='FILLER' for e in elements), f'{name} fallback filler')
    require(any(e['action']=='CLOSE' for e in elements), f'{name} close')
    if name in expected_roles:
        require(1 <= sum(e['role']==expected_roles[name] for e in elements) <= 45, f'{name} entry count')
        require(any(e['action']=='BACK_MAIN' for e in elements), f'{name} back')
    for e in menu['symbols'].values():
        require(e['role'] in roles and e['action'] in actions, f'{name} registered role/action')
        require(re.fullmatch('[A-Z][A-Z0-9_]{0,63}', e['material']), f'{name} material syntax')
        require(type(e['glow']) is bool and isinstance(e['name'],str) and isinstance(e['lore'],list), f'{name} presentation types')
        require(not e['item-model'] or re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+', e['item-model']), f'{name} model key')
        if 'custom-model-data' in e: require(type(e['custom-model-data']) is int and e['custom-model-data']>=0, f'{name} model data')
        if e['role'] in dynamic_actions:
            require(e['action']==dynamic_actions[e['role']] and e['argument']=='', f'{name} dynamic ID binding')
        elif e['action']=='SELECT_PROFILE': require(e['argument'] in profile_map, f'{name} profile reference')
        elif e['action']=='TOGGLE_BOOST': require(e['argument'] in {b['id'] for b in boosts}, f'{name} boost reference')
        for text in [menu['title'],e['name'],*e['lore']]:
            require(set(re.findall(r'\{([a-z_]+)\}',text)).issubset(allowed_placeholders), f'{name} supported UI placeholders')
messages=files['messages.yml']
for key in ('help-menu','gui-opened-preview','gui-transactions-locked','gui-cursor-not-empty','gui-mode-denied','gui-selected','gui-not-selected','gui-icon-fallback','gui-cost-line','gui-preview-only-lore'):
    require(key in messages,f'GUI message {key}')
for status in ('loading','need-source','preview','resources-missing','quote-denied','catalog-source-rejected','catalog-path-denied','catalog-no-path','catalog-no-targets','catalog-page-out-of-range','empty-list','error','expired'):
    require('gui-state-'+status in messages, f'GUI state {status}')
for key in ('busy','failed','stale','timeout'): require('gui-catalog-'+key in messages, f'GUI cache failure {key}')
for key in ('recommended','value-asc','value-desc','id'): require('gui-sort-'+key in messages, f'GUI sort {key}')
for path in (ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/gui').glob('*.java'):
    text=path.read_text(encoding='utf-8')
    require(not re.search(r'\.(withdraw|deposit|removeItem|addItem|dispatchCommand|setItemOnCursor|setCursor)\s*\(',text), f'no live side effect API in {path.name}')
    require(not re.search(r'import vn\.ledat\.itemupgrader\.(transaction|output|failure)\.',text), f'GUI no live engine coupling {path.name}')
    for key in re.findall(r'messages\.(?:send|component)\([^\n]*?"(gui-[a-z-]+)"',text):
        if not key.endswith('-'): require(key in messages,f'GUI literal message {key}')
renderer=(ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/gui/GuiRenderer.java').read_text(encoding='utf-8')
require('visualProjection(decoded,snapshot.facts().amount())' in renderer, 'icon is visual projection not raw provider clone')
projection=renderer.split('private static ItemStack visualProjection',1)[1].split('private String plain',1)[0]
require('new ItemStack' in projection and 'getPersistentDataContainer' not in projection, 'projection deliberately excludes raw PDC')
require('setItemModel' in projection and 'setCustomModelDataComponent' in projection, 'cosmetic model bridge')
listener=(ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/gui/GuiInventoryListener.java').read_text(encoding='utf-8')
require('event.setCancelled(true)' in listener and 'getRawSlot()' in listener and 'InventoryCreativeEvent' in listener, 'listener has cancellation/raw-slot/creative guards in source')
require(not re.search(r'\.(openInventory|closeInventory)\s*\(',listener), 'open/close not executed in inventory click listener')
scheduler=(ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/gui/PaperGuiScheduler.java').read_text(encoding='utf-8')
require('getGlobalRegionScheduler().run(owner' in scheduler, 'native next tick source bridge')
require('runAtFixedRate' in scheduler and 'task.cancel()' in scheduler and 'cancelTasks(owner)' not in scheduler, 'owned batched sweep and cancellation')
require('help-menu' in messages and 'ledatitemupgrader.use' in plugin['permissions'], 'menu command permission/messages')

# Phase 7 supplemental validation; these are config/source checks, not a native scheduler/client test.
a = files['menus/animation.yml']
require(type(a['preview-enabled']) is bool, 'explicit animation preview toggle')
require(1 <= a['visits-per-tick'] <= 32 and a['visits-per-tick'] <= a['maximum-sessions'] <= 128, 'animation batch/capacity bounds')
require(500 <= a['open-cooldown-ms'] <= 30000, 'animation open rate limit')
require(1000 <= a['callback-timeout-ms'] <= 10000, 'animation callback timeout')
require(100 <= a['pulse-interval-ms'] <= 1000, 'animation pulse rate limit')
require(a['default-preset'] in a['presets'], 'animation default preset exists')
for name, preset in a['presets'].items():
    require(re.fullmatch('[a-z0-9][a-z0-9_-]{0,31}', name), 'preset ID')
    require(preset['kind'] in ('NONE','QUICK','ROULETTE'), 'preset kind')
    durations = [preset[key] for key in ('intro-ms','acceleration-ms','cruise-ms','deceleration-ms','landing-ms','reveal-hold-ms')]
    require(all(type(d) is int and 0 <= d <= 10000 for d in durations), 'preset duration type/range')
    require(sum(durations) <= 15000 and 250 <= durations[-1] <= 5000, 'preset total/reveal bound')
    if preset['kind'] == 'NONE':
        require(preset['turns'] == 0 and sum(durations[:-1]) == 0, 'NONE reveal only')
    else:
        require(1 <= preset['turns'] <= 20 and preset['acceleration-ms'] >= 50 and preset['deceleration-ms'] >= 100, 'moving preset speed bounds')
menu = a['menu']
require(1 <= len(menu['matrix']) <= 6 and all(len(row) == 9 for row in menu['matrix']), 'animation matrix')
roles = [menu['symbols'][symbol] for row in menu['matrix'] for symbol in row]
require(set(roles) == {'FILLER','TRACK','STATUS','BADGE','SKIP','CLOSE'}, 'animation roles')
for role in ('STATUS','BADGE','SKIP','CLOSE'):
    require(roles.count(role) == 1, 'single animation role '+role)
track = menu['track-order']
require(4 <= len(track) <= 36 and len(track) == len(set(track)), 'unique bounded ordered track')
require(set(track) == {i for i, role in enumerate(roles) if role == 'TRACK'}, 'track covers every TRACK exactly')
require(set(menu['icons']) == {'FILLER','TRACK','MARKER','STATUS','BADGE','WIN','LOSS','SKIP','CLOSE'}, 'complete animation palette')
for icon in menu['icons'].values():
    require(re.fullmatch('[A-Z][A-Z0-9_]{0,63}', icon['material']), 'animation material syntax')
    require(type(icon['glow']) is bool and isinstance(icon['name'],str) and isinstance(icon['lore'],list), 'animation icon types')
    for text in [menu['title'],icon['name'],*icon['lore']]:
        require(set(re.findall(r'\{([^{}]+)}',text)).issubset({'stage','outcome','mode','chance','preset','prefix'}), 'animation supported placeholder')
for cue, sound in a['sounds'].items():
    require(cue in ('START','PULSE','WIN','LOSS'), 'animation sound role')
    require(not sound['key'] or re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+',sound['key']), 'animation sound key syntax')
    require(isinstance(sound['volume'],str) and Decimal('0') <= Decimal(sound['volume']) <= Decimal('2'), 'animation volume')
    require(isinstance(sound['pitch'],str) and Decimal('0.5') <= Decimal(sound['pitch']) <= Decimal('2'), 'animation pitch')
require(plugin['permissions']['ledatitemupgrader.admin.animation']['default'] == 'op', 'admin-only animation preview')
require(plugin['permissions']['ledatitemupgrader.admin']['children']['ledatitemupgrader.admin.animation'] is True, 'admin wildcard includes preview')
require('help-animation' in messages and 'animation-preview-lore' in messages and 'animation-title-preview' in messages, 'explicit preview notices')
for stage in ('intro','accelerate','spin','decelerate','land','reveal','done'):
    require('animation-stage-'+stage in messages, 'animation phase message '+stage)
for path in (ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/animation').glob('*.java'):
    text = path.read_text(encoding='utf-8')
    require(not re.search(r'\.(withdraw|deposit|removeItem|addItem|dispatchCommand|setItemOnCursor|setCursor|deserializeBytes)\s*\(',text), 'no item/money effect in '+path.name)
    require(not re.search(r'import vn\.ledat\.itemupgrader\.(transaction|output|failure)\.',text), 'native preview independent of transaction '+path.name)
listener = (ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/animation/AnimationInventoryListener.java').read_text()
require('event.setCancelled(true)' in listener and 'getRawSlot()' in listener, 'animation click cancellation/source guard')
require(not re.search(r'\.(openInventory|closeInventory)\s*\(',listener), 'animation listener defers open/close')
require('menus/animation.yml' in (ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/platform/PlatformAccess.java').read_text(), 'animation config ensured')

# Phase 8: supplemental only. Actual SQL hook semantics are exercised by the SQLite repository bridge.
history = files['history.yml']; pity = files['upgrades/pity.yml']; history_menu = files['menus/history.yml']
require(history['enabled'] is False, 'history read-side not silently enabled before schema/writer bootstrap')
require(pity['enabled'] is False and pity['policies'] == [], 'pity is opt-in with no broken sample path references')
require(history['close-behavior'] == 'DISCARD_VIEW', 'history close owns no assets')
for key, low, high in [('maximum-sessions',1,128),('maximum-queries',1,32),('maximum-pages',1,100),('cache-size',1,10000),
                       ('cache-ttl-seconds',5,1800),('request-timeout-seconds',1,30),('session-timeout-seconds',30,600),
                       ('retention-days',1,3650),('retention-batch',1,1000)]:
    require(type(history[key]) is int and low <= history[key] <= high, 'history bound '+key)
require(set(history['sounds']) == {'open','click','error','close'}, 'history sound cues')
for cue in history['sounds'].values():
    require(not cue['key'] or re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+',cue['key']), 'history sound syntax')
    require(isinstance(cue['volume'],str) and 0 <= Decimal(cue['volume']) <= 2, 'history volume')
    require(isinstance(cue['pitch'],str) and Decimal('0.5') <= Decimal(cue['pitch']) <= 2, 'history pitch')
require(all(len(row)==9 for row in history_menu['matrix']) and 1 <= len(history_menu['matrix']) <= 6, 'history layout')
symbols=history_menu['symbols']; elements=[symbols[ch] for row in history_menu['matrix'] for ch in row]
require(1 <= sum(e['role']=='INFO' for e in elements) <= 45, 'bounded read-only entries')
require(set(e['action'] for e in elements) == {'NONE','NEXT_PAGE','PREVIOUS_PAGE','REFRESH','CLOSE'}, 'history only navigation actions')
for e in elements:
    require(e['role'] in ('INFO','BUTTON','FILLER') and not e['argument'], 'history no source or target actions')
    require(e['role']!='INFO' or e['action']=='NONE', 'history entries cannot run actions')
for node in ('ledatitemupgrader.admin.history','ledatitemupgrader.admin.diagnostics'):
    require(plugin['permissions'][node]['default']=='op', 'history/diagnostics admin permission')
    require(plugin['permissions']['ledatitemupgrader.admin']['children'][node] is True, 'admin history child')
for key in ('help-history','help-statistics','help-diagnose','help-pity','history-statistics','history-unavailable','history-diagnostic-readonly',
            'history-pity-gated','quote-denied-pity-unavailable','quote-denied-pity-policy-denied'):
    require(key in messages, 'history message '+key)
for state in ('prepared','reserving','draw-intent','outcome-committed','settling','compensating','completed','aborted','reconciliation-required'):
    require('history-state-'+state in messages,'translated history state '+state)
for path in (ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/history').glob('*.java'):
    text=path.read_text(encoding='utf-8')
    require(not re.search(r'\.(withdraw|deposit|removeItem|addItem|dispatchCommand|setItemOnCursor|setCursor|deserializeBytes|claim|compareAndSet|pruneHistory)\s*\(',text), 'no native history effect in '+path.name)
listener=(ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/history/HistoryInventoryListener.java').read_text()
require('event.setCancelled(true)' in listener and 'getRawSlot()' in listener and 'InventoryCreativeEvent' in listener,'history listener safety source')
require(not re.search(r'\.(openInventory|closeInventory)\s*\(',listener),'history listener defers view modification')
renderer=(ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/history/HistoryRenderer.java').read_text()
require('new ItemStack' in renderer and 'getPersistentDataContainer' not in renderer and 'deserializeBytes' not in renderer,'history icons freshly built')
loader=(ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/config/HistoryConfigLoader.java').read_text()
require('if(compiled.enabled())throw' in loader,'pity live gate requires future owner-safe quote + atomic hook wiring')
bridge=(ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/platform/PlatformAccess.java').read_text()
for path in ('history.yml','upgrades/pity.yml','menus/history.yml'): require(path in bridge,'ensure bundled '+path)
require('registerHistoryPlaceholders' in bridge,'Platform placeholder route seam')
jdbc=(ROOT/'core/src/main/java/vn/ledat/itemupgrader/transaction/storage/JdbcTransactionRepository.java').read_text()
require(jdbc.index('hook.transitioned(c,expected,next)') < jdbc.index('if(next.terminal())'), 'atomic progress hook before terminal unlock')
require('hook.claimed(c,record)' in jdbc,'atomic pity validation at journal claim')

# Phase 9A resource/wiring checks are supplemental source checks, NOT a native compile.
management = files['storage-management.yml']
require(management['schema-mode'] == 'OFF', 'no schema changes without opt-in')
require(5 <= management['operation-timeout-seconds'] <= 120, 'schema timeout bound')
require(management['maintenance']['enabled'] is False, 'retention must be explicit opt-in')
require(60 <= management['maintenance']['interval-seconds'] <= 86400, 'maintenance interval bound')
require('ledatitemupgrader.admin.storage' in plugin['permissions'], 'storage permission')
require(plugin['permissions']['ledatitemupgrader.admin.storage']['default'] == 'op', 'storage default op')
require(plugin['permissions']['ledatitemupgrader.admin']['children']['ledatitemupgrader.admin.storage'] is True, 'admin inherits storage')
for key in ('help-storage','storage-invalid-arguments','storage-sender-only','storage-status',
            'storage-readonly-boundary','storage-check-started','storage-busy','storage-not-ready',
            'storage-request-expired','storage-recovery-header','storage-recovery-row',
            'storage-recovery-next','storage-recovery-end','history-storage-unavailable'):
    require(key in files['messages.yml'], 'storage message '+key)
require('storage-management.yml' in bridge, 'new storage YAML installed')
service = (ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/storage/PlatformStorageService.java').read_text()
require('new JdbcStorageBootstrap(' in service and 'new StorageController(' in service, 'shared bundle and lifecycle')
require('platform.query(' in service, 'Platform managed SQL query')
require('SafeFailure.describe' in service, 'redacted storage errors')
require('new TransactionEngine' not in service, 'schema bootstrap does not open live transactions')
require('new PlatformHistoryStore(platform, repositories.progress(), this::readable)' in service, 'history requires readiness')
for bad in ('withdraw(', 'deposit(', 'addItem(', 'dispatchCommand('):
    require(bad not in service, 'storage service cannot trigger external effects '+bad)
boot = (ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/bootstrap/PluginBootstrap.java').read_text()
require('storage.historyStore()' in boot, 'history receives shared store')
require('storage.close()' in boot, 'storage lifecycle shutdown')
loader = (ROOT/'paper/src/main/java/vn/ledat/itemupgrader/paper/config/ConfigLoader.java').read_text()
require('StorageManagementConfigLoader' in loader, 'storage settings in atomic config load')
require('storageManagement' in loader, 'storage snapshot published with config')

print(f'PASS supplemental resource checks={checks}, YAML files={len(files)}, PyYAML={yaml.__version__}')
print('Not a substitute for SnakeYAML/Adventure/Paper runtime validation.')
