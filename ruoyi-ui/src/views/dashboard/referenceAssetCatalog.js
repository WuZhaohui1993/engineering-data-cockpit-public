const keys = ['overviewPlant','smartPlant','qualityInspection','safetyLifting','smartGate','smartProcess','hseSensor','hseDust','progressModel','smartDrone','smartRobot','smartMobile','smartTower','headerTitle','panelTitle']
export function resolveReferenceAssets(resources) {
 const assets=Object.fromEntries(keys.map(key=>[key,resources.find(row=>row.assetCode===`xinghua-gen-${key.replace(/[A-Z]/g,m=>'-'+m.toLowerCase())}`)?.resourcePath || '']))
 return {...assets,safetyPlant:assets.smartPlant,hsePlant:assets.overviewPlant,safetyHotwork:assets.smartProcess,smartRoad:assets.hseDust,smartInspection:assets.qualityInspection}
}
