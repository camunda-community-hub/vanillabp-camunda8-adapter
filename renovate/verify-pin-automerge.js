/*
 * Proves what the pin rules of 'renovate.json' do with an update proposed for a client pin,
 * instead of leaving it to the descriptions in that file.
 *
 * Those rules decide something a reader cannot see in them: whether a pull request merges
 * itself. The step which brought this check here is the one from the last candidate of a
 * minor to its release, 8.10.0-rc3 to 8.10.0. Renovate reads that step as a PATCH, because
 * major, minor and patch stay the same and only the qualifier falls away, and a patch of a
 * pin merges itself here. The moment a preview line becomes the GA line would have gone
 * through on its own, while the line table and the line profile still said preview.
 *
 * Both halves of that sentence are asked of Renovate's own code. 'getBucket' is the
 * classifier which puts an update into the patch group, and 'applyPackageRules' is the
 * engine which lays the rules of this repository over it.
 *
 * What the check cannot answer: it reads the packageRules written in 'renovate.json' and
 * fetches none of the presets that file extends, and it asks no datasource which versions
 * exist. It says what the rules do with an update, not which updates Renovate would find.
 * Where a preset matters, it is a preset written here and merged with Renovate's own
 * merger, which is the last case below.
 *
 * Run it from the repository root, with the renovate package installed:
 *
 *   npm install --no-save renovate
 *   node renovate/verify-pin-automerge.js
 */

// The logger of Renovate warns on first use that nobody initialized it, and that warning
// reads like a defect in a green check. Initializing it at this level keeps it quiet.
process.env.LOG_LEVEL = process.env.LOG_LEVEL ?? 'fatal';

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const { init: initLogger } = require('renovate/dist/logger/index.js');
const { get } = require('renovate/dist/modules/versioning/index.js');
const { mergeChildConfig } = require('renovate/dist/config/utils.js');
const { getBucket } = require('renovate/dist/workers/repository/process/lookup/bucket.js');
const { applyPackageRules } = require('renovate/dist/util/package-rules/index.js');

const configFile = path.join(__dirname, '..', 'renovate.json');
const config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
const maven = get('maven');

// The two switches which decide the group of a client pin. Separating a patch from a minor
// is read out of the configuration instead of being stated again here, because that is where
// the rules say a patch and a minor of a pin mean different things. Separating a major from a
// minor is Renovate's own default.
const separating = config.packageRules.find((rule) => rule.separateMinorPatch !== undefined);
assert.ok(separating, 'no package rule of renovate.json separates a patch of a pin from a minor');
const grouping = {
  separateMajorMinor: true,
  separateMinorPatch: separating.separateMinorPatch,
};

function bucketOf(currentVersion, newVersion) {
  return getBucket(grouping, currentVersion, newVersion, maven);
}

// An update as the custom managers of 'renovate.json' hand it on: the pin is the whole value
// of the property, so the value and the version are the same string.
async function resolve({ depName, currentValue, newValue }) {
  const updateType = bucketOf(currentValue, newValue);
  const resolved = await applyPackageRules({
    manager: 'regex',
    datasource: 'maven',
    versioning: 'maven',
    depName,
    packageName: 'io.camunda:camunda-client-java',
    currentValue,
    currentVersion: currentValue,
    newValue,
    newVersion: newValue,
    updateType,
    packageRules: config.packageRules,
  });
  return { ...resolved, updateType };
}

async function main() {
  await initLogger();

  // the finding: only the qualifier falls away, so the release of a minor arrives as a patch
  assert.strictEqual(
    bucketOf('8.10.0-rc3', '8.10.0'),
    'patch',
    'the step from the last candidate to the release should be read as a patch');
  assert.strictEqual(bucketOf('8.10.0', '8.10.1'), 'patch');
  assert.strictEqual(bucketOf('8.9.18', '8.10.0'), 'minor');
  console.log('ok   8.10.0-rc3 -> 8.10.0 is a patch, the same group as 8.10.0 -> 8.10.1');

  // a patch inside a settled line is upkeep and merges itself, which is why the rule exists
  const patch = await resolve({
    depName: 'camunda-client-java (line 8.10)',
    currentValue: '8.10.0',
    newValue: '8.10.1',
  });
  assert.strictEqual(patch.automerge, true, 'a patch of a released pin should still merge itself');
  assert.ok(patch.addLabels.includes('camunda8-line-pin'));
  console.log('ok   8.10.0 -> 8.10.1 merges itself and is labelled as a pin move');

  // the same group, and it waits, because the pin it starts from is a pre-release
  const release = await resolve({
    depName: 'camunda-client-java (line 8.10)',
    currentValue: '8.10.0-rc3',
    newValue: '8.10.0',
  });
  assert.strictEqual(release.updateType, 'patch');
  assert.strictEqual(
    release.automerge,
    false,
    'the step from a pre-release to the release must not merge itself');
  assert.strictEqual(release.platformAutomerge, false);
  assert.ok(
    release.addLabels.includes('camunda8-pre-release-pin'),
    'the pull request should say on it that the pin it starts from is a pre-release');
  assert.strictEqual(release.additionalBranchPrefix, 'camunda8-pre-release-pin-');
  console.log('ok   8.10.0-rc3 -> 8.10.0 waits for a person, and its branch name says why');

  // a pre-release is not only the last step before a release, and none of them merges itself
  for (const [currentValue, newValue] of [
    ['8.11.0-alpha1', '8.11.0-alpha2'],
    ['8.11.0-alpha5-rc2', '8.11.0-rc1'],
    ['8.11.0-rc1', '8.11.0'],
  ]) {
    const update = await resolve({
      depName: 'camunda-client-java (line 8.11 preview)',
      currentValue,
      newValue,
    });
    assert.strictEqual(
      update.automerge,
      false,
      `${currentValue} -> ${newValue} must not merge itself`);
    console.log(`ok   ${currentValue} -> ${newValue} waits for a person as well`);
  }

  // Every pin this repository declares, read from the managers themselves. A line added
  // later is a pin nobody wrote this check for, and it has to be covered the same way,
  // because falling back into the automerge is the failure this is all about.
  for (const manager of config.customManagers) {
    const depName = manager.depNameTemplate;
    const update = await resolve({
      depName,
      currentValue: '9.0.0-rc1',
      newValue: '9.0.0',
    });
    assert.strictEqual(
      update.automerge,
      false,
      `the pin '${depName}' would merge the step onto a release on its own`);
    console.log(`ok   the pin '${depName}' is covered`);
  }

  // What a minor does was never in question, and it stays untouched: it waits for approval
  // on the dependency dashboard and carries the prefix which says it crosses a line.
  const minor = await resolve({
    depName: 'camunda-client-java (line 8.9)',
    currentValue: '8.9.18',
    newValue: '8.10.0',
  });
  assert.strictEqual(minor.updateType, 'minor');
  assert.strictEqual(minor.automerge, false);
  assert.strictEqual(minor.dependencyDashboardApproval, true);
  assert.strictEqual(minor.additionalBranchPrefix, 'camunda8-line-boundary-');
  assert.ok(minor.addLabels.includes('camunda8-line-boundary'));
  console.log('ok   8.9.18 -> 8.10.0 is still a line boundary, with its own branch prefix');

  // The maven manager sees the same client through dependencyManagement. Only the custom
  // managers own these pins, so that second sighting is turned off and stays turned off.
  const duplicate = await applyPackageRules({
    manager: 'maven',
    datasource: 'maven',
    versioning: 'maven',
    depName: 'io.camunda:camunda-client-java',
    packageName: 'io.camunda:camunda-client-java',
    currentValue: '8.10.0',
    currentVersion: '8.10.0',
    newValue: '8.10.1',
    newVersion: '8.10.1',
    updateType: 'patch',
    packageRules: config.packageRules,
  });
  assert.strictEqual(duplicate.enabled, false);
  assert.strictEqual(duplicate.skipReason, 'package-rules');
  console.log('ok   the maven manager proposes nothing for a pin a custom manager owns');

  // This repository extends a preset of the organisation, and Renovate reads the rules of a
  // preset before the ones written here. So the exception has to hold over a preset which
  // automerges every patch. The preset below stands for that, and the two configurations are
  // put together by Renovate's own merger rather than by an assumption about the order.
  const overPreset = mergeChildConfig(
    {
      packageRules: [{
        description: 'stands for a preset which lets every patch merge itself',
        matchUpdateTypes: ['patch'],
        automerge: true,
        platformAutomerge: true,
      }],
    },
    config);
  const held = await applyPackageRules({
    manager: 'regex',
    datasource: 'maven',
    versioning: 'maven',
    depName: 'camunda-client-java (line 8.10)',
    packageName: 'io.camunda:camunda-client-java',
    currentValue: '8.10.0-rc3',
    currentVersion: '8.10.0-rc3',
    newValue: '8.10.0',
    newVersion: '8.10.0',
    updateType: 'patch',
    packageRules: overPreset.packageRules,
  });
  assert.strictEqual(
    held.automerge,
    false,
    'a preset which automerges every patch must not reach this pin');
  console.log('ok   the exception holds over a preset which automerges every patch');

  console.log('\npin rules verified against renovate ' + require('renovate/package.json').version);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
