FeatureScript 3083;
import(path : "onshape/std/geometry.fs", version : "3083.0");

// =====================================================================================
// lpgVessel — вертикальный сосуд линии очистки СУГ (демеркаптанизация, наполнение
// аэрозольных баллонов). Один и тот же Custom Feature используется для адсорбера
// (hasGrid = true) и буферной ёмкости (hasGrid = false).
//
// Геометрия упрощена (визуальная / компоновочная модель):
//   * корпус — полый цилиндр с плоскими крышками и скруглением по кромкам;
//   * лазы DN150 сверху и снизу: патрубок + фланец + отдельная слепая крышка (отдельный Part);
//   * штуцера: вход радиальный в обечайке, выход осевой в нижней крышке,
//     манометр и ППК — осевые в верхней крышке;
//   * опоры — равнополочный уголок, 3 или 4 шт.;
//   * опорная решётка сорбента — отдельный Part (только адсорбер).
//
// ПРИМЕЧАНИЕ (EFV): на входном и выходном штуцерах устанавливается скоростной
// отсечной клапан (excess flow valve) — NFPA 58 §5.9 / ת"י 158. Отдельная геометрия
// клапана в модели не строится; штуцер моделируется как патрубок под его установку.
//
// ПРИМЕЧАНИЕ (прочность): толщина плоских крышек в модели равна толщине обечайки.
// Для реального сосуда под давлением СУГ плоское днище рассчитывается отдельно
// (ASME VIII Div.1 UG-34 / EN 13445-3 §10) и получается в разы толще обечайки.
// =====================================================================================

// ---------------- Константы (безразмерные величины в UI не выносятся) ----------------
const BOLT_COUNT = 8;                           // шпилек на фланце лаза
const BOLT_HEAD_DIA_FACTOR = 1.6;               // диаметр гайки / диаметр шпильки
const BOLT_HEAD_HEIGHT_FACTOR = 0.8;            // высота гайки / диаметр шпильки
const GASKET_GAP = 3 * millimeter;              // зазор под прокладку (фланец не касается крышки)
const CUT_OVERRUN = 1 * millimeter;             // перебег режущих операций
const GRID_CLEARANCE = 2 * millimeter;          // радиальный зазор решётки к стенке
const LEG_WELD_LENGTH = 200 * millimeter;       // длина нахлёста опоры на обечайку
const LEG_EMBED = 2 * millimeter;               // заглубление пятки уголка в стенку (для слияния)
const MIN_CLEARANCE = 2 * millimeter;           // минимальный конструктивный зазор в проверках

// ---------------- Диапазоны параметров ----------------
const VESSEL_OD_BOUNDS =
{
    (meter)      : [0.1, 0.61, 5],
    (centimeter) : 61,
    (millimeter) : 610,
    (inch)       : 24,
    (foot)       : 2,
    (yard)       : 0.667
} as LengthBoundSpec;

const CYL_LENGTH_BOUNDS =
{
    (meter)      : [0.2, 1.5, 20],
    (centimeter) : 150,
    (millimeter) : 1500,
    (inch)       : 60,
    (foot)       : 5,
    (yard)       : 1.667
} as LengthBoundSpec;

const WALL_BOUNDS =
{
    (meter)      : [0.001, 0.006, 0.05],
    (centimeter) : 0.6,
    (millimeter) : 6,
    (inch)       : 0.25,
    (foot)       : 0.02,
    (yard)       : 0.0066
} as LengthBoundSpec;

const FILLET_BOUNDS =
{
    (meter)      : [0, 0.025, 0.5],
    (centimeter) : 2.5,
    (millimeter) : 25,
    (inch)       : 1,
    (foot)       : 0.08,
    (yard)       : 0.028
} as LengthBoundSpec;

const LEG_HEIGHT_BOUNDS =
{
    (meter)      : [0.05, 0.5, 5],
    (centimeter) : 50,
    (millimeter) : 500,
    (inch)       : 20,
    (foot)       : 1.6,
    (yard)       : 0.55
} as LengthBoundSpec;

const ANGLE_SIZE_BOUNDS =
{
    (meter)      : [0.02, 0.05, 0.25],
    (centimeter) : 5,
    (millimeter) : 50,
    (inch)       : 2,
    (foot)       : 0.16,
    (yard)       : 0.055
} as LengthBoundSpec;

const ANGLE_THK_BOUNDS =
{
    (meter)      : [0.002, 0.005, 0.03],
    (centimeter) : 0.5,
    (millimeter) : 5,
    (inch)       : 0.1875,
    (foot)       : 0.016,
    (yard)       : 0.0055
} as LengthBoundSpec;

const MANWAY_PIPE_OD_BOUNDS =
{
    (meter)      : [0.03, 0.1683, 1],
    (centimeter) : 16.83,
    (millimeter) : 168.3,
    (inch)       : 6.625,
    (foot)       : 0.552,
    (yard)       : 0.184
} as LengthBoundSpec;

const PIPE_WALL_BOUNDS =
{
    (meter)      : [0.001, 0.00711, 0.05],
    (centimeter) : 0.711,
    (millimeter) : 7.11,
    (inch)       : 0.28,
    (foot)       : 0.0233,
    (yard)       : 0.0078
} as LengthBoundSpec;

const NECK_LENGTH_BOUNDS =
{
    (meter)      : [0.02, 0.15, 1],
    (centimeter) : 15,
    (millimeter) : 150,
    (inch)       : 6,
    (foot)       : 0.5,
    (yard)       : 0.164
} as LengthBoundSpec;

const FLANGE_OD_BOUNDS =
{
    (meter)      : [0.05, 0.2794, 1.5],
    (centimeter) : 27.94,
    (millimeter) : 279.4,
    (inch)       : 11,
    (foot)       : 0.9167,
    (yard)       : 0.3056
} as LengthBoundSpec;

const FLANGE_THK_BOUNDS =
{
    (meter)      : [0.005, 0.0254, 0.2],
    (centimeter) : 2.54,
    (millimeter) : 25.4,
    (inch)       : 1,
    (foot)       : 0.0833,
    (yard)       : 0.0278
} as LengthBoundSpec;

const BOLT_CIRCLE_BOUNDS =
{
    (meter)      : [0.04, 0.2413, 1.4],
    (centimeter) : 24.13,
    (millimeter) : 241.3,
    (inch)       : 9.5,
    (foot)       : 0.7917,
    (yard)       : 0.2639
} as LengthBoundSpec;

const BOLT_DIA_BOUNDS =
{
    (meter)      : [0.006, 0.01905, 0.1],
    (centimeter) : 1.905,
    (millimeter) : 19.05,
    (inch)       : 0.75,
    (foot)       : 0.0625,
    (yard)       : 0.0208
} as LengthBoundSpec;

const NOZZLE_1IN_BOUNDS =
{
    (meter)      : [0.006, 0.0334, 0.3],
    (centimeter) : 3.34,
    (millimeter) : 33.4,
    (inch)       : 1.315,
    (foot)       : 0.1096,
    (yard)       : 0.0365
} as LengthBoundSpec;

const NOZZLE_HALF_IN_BOUNDS =
{
    (meter)      : [0.006, 0.0213, 0.3],
    (centimeter) : 2.13,
    (millimeter) : 21.3,
    (inch)       : 0.84,
    (foot)       : 0.07,
    (yard)       : 0.0233
} as LengthBoundSpec;

const NOZZLE_3Q_IN_BOUNDS =
{
    (meter)      : [0.006, 0.0267, 0.3],
    (centimeter) : 2.67,
    (millimeter) : 26.7,
    (inch)       : 1.05,
    (foot)       : 0.0875,
    (yard)       : 0.0292
} as LengthBoundSpec;

const NOZZLE_WALL_BOUNDS =
{
    (meter)      : [0.001, 0.00373, 0.03],
    (centimeter) : 0.373,
    (millimeter) : 3.73,
    (inch)       : 0.147,
    (foot)       : 0.01225,
    (yard)       : 0.00408
} as LengthBoundSpec;

const NOZZLE_LENGTH_BOUNDS =
{
    (meter)      : [0.02, 0.1, 1],
    (centimeter) : 10,
    (millimeter) : 100,
    (inch)       : 4,
    (foot)       : 0.333,
    (yard)       : 0.111
} as LengthBoundSpec;

const INLET_FROM_TOP_BOUNDS =
{
    (meter)      : [0.02, 0.3, 20],
    (centimeter) : 30,
    (millimeter) : 300,
    (inch)       : 12,
    (foot)       : 1,
    (yard)       : 0.333
} as LengthBoundSpec;

const TOP_NOZZLE_OFFSET_BOUNDS =
{
    (meter)      : [0, 0.05, 0.5],
    (centimeter) : 5,
    (millimeter) : 50,
    (inch)       : 2,
    (foot)       : 0.1667,
    (yard)       : 0.0556
} as LengthBoundSpec;

const GRID_ELEVATION_BOUNDS =
{
    (meter)      : [0.001, 0.1, 5],
    (centimeter) : 10,
    (millimeter) : 100,
    (inch)       : 4,
    (foot)       : 0.333,
    (yard)       : 0.111
} as LengthBoundSpec;

const INLET_ANGLE_BOUNDS =
{
    (degree) : [0, 0, 360],
    (radian) : 0
} as AngleBoundSpec;

// ---------------- Перечисления (вместо "голых" number-параметров) ----------------
export enum LpgLegCount
{
    annotation { "Name" : "3 legs" }
    THREE,
    annotation { "Name" : "4 legs" }
    FOUR
}

// =====================================================================================
// FEATURE
// =====================================================================================
annotation { "Feature Type Name" : "LPG vessel" }
export const lpgVessel = defineFeature(function(context is Context, id is Id, definition is map)
    precondition
    {
        // --- Установка ---
        annotation { "Name" : "Placement (plane or mate connector)", "Filter" : QueryFilterCompound.ALLOWS_PLANE, "MaxNumberOfPicks" : 1 }
        definition.placement is Query;

        // --- Корпус ---
        annotation { "Name" : "Shell outer diameter" }
        isLength(definition.vesselOD, VESSEL_OD_BOUNDS);

        annotation { "Name" : "Cylinder length" }
        isLength(definition.cylLength, CYL_LENGTH_BOUNDS);

        annotation { "Name" : "Wall thickness" }
        isLength(definition.wallThickness, WALL_BOUNDS);

        annotation { "Name" : "Head edge fillet radius (0 = none)" }
        isLength(definition.filletRadius, FILLET_BOUNDS);

        // --- Опоры ---
        annotation { "Name" : "Number of legs" }
        definition.legCount is LpgLegCount;

        annotation { "Name" : "Leg height (to shell bottom)" }
        isLength(definition.legHeight, LEG_HEIGHT_BOUNDS);

        annotation { "Name" : "Leg angle size" }
        isLength(definition.legAngleSize, ANGLE_SIZE_BOUNDS);

        annotation { "Name" : "Leg angle thickness" }
        isLength(definition.legAngleThickness, ANGLE_THK_BOUNDS);

        // --- Лазы (верх/низ) ---
        annotation { "Name" : "Manway pipe OD" }
        isLength(definition.manwayPipeOD, MANWAY_PIPE_OD_BOUNDS);

        annotation { "Name" : "Manway pipe wall" }
        isLength(definition.manwayPipeWall, PIPE_WALL_BOUNDS);

        annotation { "Name" : "Manway neck length" }
        isLength(definition.neckLength, NECK_LENGTH_BOUNDS);

        annotation { "Name" : "Flange OD" }
        isLength(definition.flangeOD, FLANGE_OD_BOUNDS);

        annotation { "Name" : "Flange thickness" }
        isLength(definition.flangeThickness, FLANGE_THK_BOUNDS);

        annotation { "Name" : "Bolt circle diameter" }
        isLength(definition.boltCircleDiameter, BOLT_CIRCLE_BOUNDS);

        annotation { "Name" : "Bolt diameter" }
        isLength(definition.boltDiameter, BOLT_DIA_BOUNDS);

        annotation { "Name" : "Blind cover thickness" }
        isLength(definition.coverThickness, FLANGE_THK_BOUNDS);

        // --- Штуцера ---
        annotation { "Name" : "Nozzle length (all nozzles)" }
        isLength(definition.nozzleLength, NOZZLE_LENGTH_BOUNDS);

        annotation { "Name" : "Nozzle wall (all nozzles)" }
        isLength(definition.nozzleWall, NOZZLE_WALL_BOUNDS);

        annotation { "Name" : "Inlet nozzle OD (radial, EFV)" }
        isLength(definition.inletOD, NOZZLE_1IN_BOUNDS);

        annotation { "Name" : "Inlet axis from top of cylinder" }
        isLength(definition.inletFromTop, INLET_FROM_TOP_BOUNDS);

        annotation { "Name" : "Inlet angular position" }
        isAngle(definition.inletAngle, INLET_ANGLE_BOUNDS);

        annotation { "Name" : "Outlet nozzle OD (bottom cover, EFV)" }
        isLength(definition.outletOD, NOZZLE_1IN_BOUNDS);

        annotation { "Name" : "Pressure gauge nozzle OD (top cover)" }
        isLength(definition.gaugeOD, NOZZLE_HALF_IN_BOUNDS);

        annotation { "Name" : "Relief valve nozzle OD (top cover)" }
        isLength(definition.reliefOD, NOZZLE_3Q_IN_BOUNDS);

        annotation { "Name" : "Gauge / relief offset from axis" }
        isLength(definition.topNozzleOffset, TOP_NOZZLE_OFFSET_BOUNDS);

        // --- Опорная решётка (адсорбер) ---
        annotation { "Name" : "Internal support grid (adsorber)", "Default" : true }
        definition.hasGrid is boolean;

        if (definition.hasGrid)
        {
            annotation { "Name" : "Grid elevation above inner bottom" }
            isLength(definition.gridElevation, GRID_ELEVATION_BOUNDS);

            annotation { "Name" : "Grid thickness" }
            isLength(definition.gridThickness, WALL_BOUNDS);
        }
    }
    {
        const base = getPlacementPlane(context, definition.placement);

        validateVesselInput(context, id, definition);

        const zBottom = definition.legHeight;
        const zTop = definition.legHeight + definition.cylLength;

        // 1. Корпус: цилиндр -> скругление -> полое тело
        buildVesselShell(context, id + "vessel", base, definition);

        // 2. Лазы: верхний (dir = +1) с манометром и ППК, нижний (dir = -1) с выходом
        buildManway(context, id + "topManway", base, zTop, 1, definition, true);
        buildManway(context, id + "bottomManway", base, zBottom, -1, definition, false);

        // 3. Радиальный входной штуцер
        buildRadialInlet(context, id + "inlet", base, definition);

        // 4. Опоры из уголка
        buildLegs(context, id + "legs", base, definition);

        // 5. Опорная решётка сорбента (строится последней — не попадает под вырезы)
        if (definition.hasGrid)
        {
            buildSupportGrid(context, id + "grid", base, definition);
        }
    });

// =====================================================================================
// Базовая плоскость установки
// =====================================================================================
function getPlacementPlane(context is Context, placement is Query) returns Plane
{
    var q = placement;
    if (size(evaluateQuery(context, q)) == 0)
    {
        q = qCreatedBy(makeId("Top"), EntityType.FACE);
    }

    const pl = try silent(evPlane(context, { "face" : q }));
    if (pl != undefined)
    {
        return pl;
    }

    const cs = try silent(evMateConnector(context, { "mateConnector" : q }));
    if (cs != undefined)
    {
        return plane(cs.origin, cs.zAxis, cs.xAxis);
    }

    throw regenError("Placement must be a planar face, construction plane or mate connector", ["placement"]);
}

// Горизонтальная плоскость на отметке elevation вдоль оси сосуда
function elevationPlane(base is Plane, elevation is ValueWithUnits) returns Plane
{
    return plane(base.origin + base.normal * elevation, base.normal, base.x);
}

// Вторая горизонтальная ось сосуда (у Plane нет поля .y)
function vesselYAxis(base is Plane) returns Vector
{
    return cross(base.normal, base.x);
}

// =====================================================================================
// Проверка входных данных
// =====================================================================================
function validateVesselInput(context is Context, id is Id, definition is map)
{
    const ro = definition.vesselOD / 2;
    const t = definition.wallThickness;
    const ri = ro - t;
    const rf = definition.filletRadius;
    const pipeR = definition.manwayPipeOD / 2;
    const boreR = pipeR - definition.manwayPipeWall;
    const headR = BOLT_HEAD_DIA_FACTOR * definition.boltDiameter / 2;
    const headH = BOLT_HEAD_HEIGHT_FACTOR * definition.boltDiameter;
    const bcR = definition.boltCircleDiameter / 2;
    const nw = definition.nozzleWall;

    if (ri <= 0 * meter)
    {
        throw regenError("Wall thickness must be less than shell radius", ["wallThickness"]);
    }
    if (rf > 0 * meter && rf < t + MIN_CLEARANCE)
    {
        throw regenError("Fillet radius must be 0 or greater than wall thickness + 2 mm", ["filletRadius"]);
    }
    if (2 * rf >= definition.cylLength - 2 * MIN_CLEARANCE)
    {
        throw regenError("Fillet radius too large for cylinder length", ["filletRadius"]);
    }

    // Лаз
    if (boreR <= 0 * meter)
    {
        throw regenError("Manway pipe wall must be less than pipe radius", ["manwayPipeWall"]);
    }
    if (pipeR >= ro - rf - MIN_CLEARANCE)
    {
        throw regenError("Manway pipe does not fit on the flat part of the head", ["manwayPipeOD"]);
    }
    if (definition.flangeOD / 2 <= pipeR + MIN_CLEARANCE)
    {
        throw regenError("Flange OD must exceed manway pipe OD", ["flangeOD"]);
    }
    if (bcR - headR <= pipeR + MIN_CLEARANCE)
    {
        throw regenError("Bolt circle too small: nuts intersect manway pipe", ["boltCircleDiameter"]);
    }
    if (bcR + headR > definition.flangeOD / 2 + MIN_CLEARANCE)
    {
        reportFeatureWarning(context, id, "Bolt nuts overhang flange OD — check bolt circle / bolt diameter");
    }
    if (definition.neckLength <= headH + MIN_CLEARANCE)
    {
        throw regenError("Manway neck too short for flange nuts", ["neckLength"]);
    }

    // Штуцера
    if (nw >= definition.inletOD / 2 || nw >= definition.outletOD / 2 || nw >= definition.gaugeOD / 2 || nw >= definition.reliefOD / 2)
    {
        throw regenError("Nozzle wall must be less than nozzle radius", ["nozzleWall"]);
    }
    const inletR = definition.inletOD / 2;
    if (inletR >= ri - MIN_CLEARANCE)
    {
        throw regenError("Inlet nozzle too large for shell", ["inletOD"]);
    }
    if (definition.inletFromTop - inletR < rf + MIN_CLEARANCE)
    {
        throw regenError("Inlet nozzle intersects top head / fillet", ["inletFromTop"]);
    }
    if (definition.inletFromTop + inletR > definition.cylLength - rf - MIN_CLEARANCE)
    {
        throw regenError("Inlet nozzle below cylindrical part", ["inletFromTop"]);
    }
    if (definition.outletOD / 2 > boreR - MIN_CLEARANCE)
    {
        throw regenError("Outlet nozzle larger than manway bore", ["outletOD"]);
    }
    const gaugeR = definition.gaugeOD / 2;
    const reliefR = definition.reliefOD / 2;
    const off = definition.topNozzleOffset;
    if (off + max(gaugeR, reliefR) > boreR - MIN_CLEARANCE)
    {
        throw regenError("Gauge / relief nozzles fall outside manway bore", ["topNozzleOffset"]);
    }
    if (2 * off < gaugeR + reliefR + MIN_CLEARANCE)
    {
        throw regenError("Gauge and relief nozzles overlap — increase offset", ["topNozzleOffset"]);
    }

    // Опоры
    if (definition.legAngleThickness >= definition.legAngleSize / 2)
    {
        throw regenError("Leg angle thickness too large for angle size", ["legAngleThickness"]);
    }
    const bottomStack = definition.neckLength + definition.flangeThickness + GASKET_GAP + definition.coverThickness + max(definition.nozzleLength, headH);
    if (definition.legHeight <= bottomStack)
    {
        reportFeatureWarning(context, id, "Legs shorter than bottom manway + outlet nozzle: nozzle crosses placement plane");
    }

    // Решётка
    if (definition.hasGrid)
    {
        if (definition.gridElevation < max(rf - t, 0 * meter) + MIN_CLEARANCE)
        {
            throw regenError("Support grid intersects bottom inner fillet — raise grid", ["gridElevation"]);
        }
        if (definition.gridElevation + definition.gridThickness > definition.cylLength - 2 * t - rf)
        {
            throw regenError("Support grid above vessel interior", ["gridElevation"]);
        }
    }
}

// =====================================================================================
// Базовый примитив: круги в эскизе на плоскости + extrude
// =====================================================================================
function extrudeCirclesOnPlane(context is Context, id is Id, sketchPlane is Plane, centers is array,
    radius is ValueWithUnits, depth is ValueWithUnits, flip is boolean, opType is NewBodyOperationType)
{
    const sketchId = id + "sketch";
    const sketch = newSketchOnPlane(context, sketchId, { "sketchPlane" : sketchPlane });
    for (var i = 0; i < size(centers); i += 1)
    {
        skCircle(sketch, "circle" ~ i, {
                    "center" : centers[i],
                    "radius" : radius
                });
    }
    skSolve(sketch);

    extrude(context, id + "extrude", {
                "entities" : qSketchRegion(sketchId),
                "endBound" : BoundingType.BLIND,
                "depth" : depth,
                "oppositeDirection" : flip,
                "operationType" : opType,
                "defaultScope" : true
            });

    opDeleteBodies(context, id + "deleteSketch", { "entities" : qCreatedBy(sketchId, EntityType.BODY) });
}

// Осевой вариант: sStart — расстояние от наружной плоскости крышки корпуса (zFace) наружу,
// dir = +1 (верх, вдоль нормали) / -1 (низ, против нормали)
function extrudeCirclesAxial(context is Context, id is Id, base is Plane, zFace is ValueWithUnits, dir is number,
    sStart is ValueWithUnits, centers is array, radius is ValueWithUnits, depth is ValueWithUnits, opType is NewBodyOperationType)
{
    extrudeCirclesOnPlane(context, id, elevationPlane(base, zFace + dir * sStart), centers, radius, depth, dir < 0, opType);
}

function boltCircleCenters(radius is ValueWithUnits) returns array
{
    var pts = [];
    for (var i = 0; i < BOLT_COUNT; i += 1)
    {
        // шпильки вразбежку с осями (половина шага)
        const ang = (i + 0.5) * 360 * degree / BOLT_COUNT;
        pts = append(pts, vector(cos(ang), sin(ang)) * radius);
    }
    return pts;
}

// =====================================================================================
// 1. Корпус
// =====================================================================================
function buildVesselShell(context is Context, id is Id, base is Plane, definition is map)
{
    const bodyExtrudeId = id + "body" + "extrude";

    // Сплошной цилиндр (базовое тело — NEW)
    extrudeCirclesOnPlane(context, id + "body", elevationPlane(base, definition.legHeight),
        [vector(0, 0) * meter], definition.vesselOD / 2, definition.cylLength, false, NewBodyOperationType.NEW);

    // Скругление кромок крышек (до shell: внутренний радиус = R - t)
    if (definition.filletRadius > 0 * meter)
    {
        opFillet(context, id + "fillet", {
                    "entities" : qGeometry(qCreatedBy(bodyExtrudeId, EntityType.EDGE), GeometryType.CIRCLE),
                    "radius" : definition.filletRadius
                });
    }

    // Полое тело (замкнутая полость внутрь)
    shell(context, id + "hollow", {
                "isHollow" : true,
                "parts" : qCreatedBy(bodyExtrudeId, EntityType.BODY),
                "entities" : qNothing(),
                "thickness" : definition.wallThickness,
                "oppositeDirection" : false
            });
}

// =====================================================================================
// 2. Лаз: патрубок + фланец + гайки + проход; слепая крышка + головки + штуцера
// =====================================================================================
function buildManway(context is Context, id is Id, base is Plane, zFace is ValueWithUnits, dir is number,
    definition is map, isTop is boolean)
{
    const t = definition.wallThickness;
    const neckL = definition.neckLength;
    const fT = definition.flangeThickness;
    const cT = definition.coverThickness;
    const pipeR = definition.manwayPipeOD / 2;
    const boreR = pipeR - definition.manwayPipeWall;
    const flangeR = definition.flangeOD / 2;
    const headR = BOLT_HEAD_DIA_FACTOR * definition.boltDiameter / 2;
    const headH = BOLT_HEAD_HEIGHT_FACTOR * definition.boltDiameter;
    const boltPts = boltCircleCenters(definition.boltCircleDiameter / 2);
    const center = [vector(0, 0) * meter];
    const sCover = neckL + fT + GASKET_GAP;

    // Патрубок (сливается с крышкой корпуса)
    extrudeCirclesAxial(context, id + "neck", base, zFace, dir, 0 * meter, center, pipeR, neckL, NewBodyOperationType.ADD);

    // Фланец
    extrudeCirclesAxial(context, id + "flange", base, zFace, dir, neckL, center, flangeR, fT, NewBodyOperationType.ADD);

    // Гайки с тыльной стороны фланца (визуально)
    extrudeCirclesAxial(context, id + "nuts", base, zFace, dir, neckL - headH, boltPts, headR, headH, NewBodyOperationType.ADD);

    // Проход DN: от полости через крышку корпуса, патрубок и фланец (не доходит до слепой крышки)
    extrudeCirclesAxial(context, id + "bore", base, zFace, dir, -(t + CUT_OVERRUN), center, boreR,
        t + neckL + fT + 2 * CUT_OVERRUN, NewBodyOperationType.REMOVE);

    // Слепая крышка — отдельное тело (зазор под прокладку исключает слияние с фланцем)
    extrudeCirclesAxial(context, id + "cover", base, zFace, dir, sCover, center, flangeR, cT, NewBodyOperationType.NEW);

    // Головки шпилек на крышке (сливаются с крышкой)
    extrudeCirclesAxial(context, id + "heads", base, zFace, dir, sCover + cT, boltPts, headR, headH, NewBodyOperationType.ADD);

    // Штуцера в слепой крышке
    if (isTop)
    {
        // Манометр: +X, ППК: -X
        buildCoverNozzle(context, id + "gauge", base, zFace, dir, sCover, cT,
            vector(1, 0) * definition.topNozzleOffset, definition.gaugeOD / 2, definition);
        buildCoverNozzle(context, id + "relief", base, zFace, dir, sCover, cT,
            vector(-1, 0) * definition.topNozzleOffset, definition.reliefOD / 2, definition);
    }
    else
    {
        // Выход (EFV устанавливается на штуцер)
        buildCoverNozzle(context, id + "outlet", base, zFace, dir, sCover, cT,
            vector(0, 0) * meter, definition.outletOD / 2, definition);
    }
}

// Осевой штуцер в слепой крышке: патрубок ADD + сквозной проход REMOVE
function buildCoverNozzle(context is Context, id is Id, base is Plane, zFace is ValueWithUnits, dir is number,
    sCover is ValueWithUnits, cT is ValueWithUnits, center is Vector, r is ValueWithUnits, definition is map)
{
    const rb = r - definition.nozzleWall;
    const halfGap = GASKET_GAP / 2;

    extrudeCirclesAxial(context, id + "pipe", base, zFace, dir, sCover + cT, [center], r,
        definition.nozzleLength, NewBodyOperationType.ADD);

    // Проход начинается в зазоре прокладки — фланец не затрагивается
    extrudeCirclesAxial(context, id + "bore", base, zFace, dir, sCover - halfGap, [center], rb,
        halfGap + cT + definition.nozzleLength + CUT_OVERRUN, NewBodyOperationType.REMOVE);
}

// =====================================================================================
// 3. Радиальный входной штуцер (EFV — см. примечание в шапке)
// =====================================================================================
function buildRadialInlet(context is Context, id is Id, base is Plane, definition is map)
{
    const ro = definition.vesselOD / 2;
    const ri = ro - definition.wallThickness;
    const r = definition.inletOD / 2;
    const rb = r - definition.nozzleWall;
    const zc = definition.legHeight + definition.cylLength - definition.inletFromTop;

    const yAxis = vesselYAxis(base);
    const radialDir = base.x * cos(definition.inletAngle) + yAxis * sin(definition.inletAngle);
    const axisPoint = base.origin + base.normal * zc;
    const center = [vector(0, 0) * meter];

    // Патрубок от внутренней поверхности стенки наружу (в полость не выступает)
    const pipePlane = plane(axisPoint + radialDir * ri, radialDir, base.normal);
    extrudeCirclesOnPlane(context, id + "pipe", pipePlane, center, r,
        (ro - ri) + definition.nozzleLength, false, NewBodyOperationType.ADD);

    // Проход: старт из полости с учётом кривизны стенки
    const xb = sqrt(ri * ri - rb * rb) - CUT_OVERRUN;
    const borePlane = plane(axisPoint + radialDir * xb, radialDir, base.normal);
    extrudeCirclesOnPlane(context, id + "bore", borePlane, center, rb,
        ro + definition.nozzleLength - xb + CUT_OVERRUN, false, NewBodyOperationType.REMOVE);
}

// =====================================================================================
// 4. Опоры из равнополочного уголка
// =====================================================================================
function buildLegs(context is Context, id is Id, base is Plane, definition is map)
{
    const n = definition.legCount == LpgLegCount.FOUR ? 4 : 3;
    const ro = definition.vesselOD / 2;
    const a = definition.legAngleSize;
    const s = definition.legAngleThickness;
    const embed = min(LEG_EMBED, definition.wallThickness / 2);
    const weld = min(max(LEG_WELD_LENGTH, definition.filletRadius + 50 * millimeter), definition.cylLength / 2);

    const sketchId = id + "sketch";
    const sketch = newSketchOnPlane(context, sketchId, { "sketchPlane" : base });

    for (var i = 0; i < n; i += 1)
    {
        // Опоры смещены на полшага от оси X (вход при inletAngle = 0 — между опорами)
        const ang = (i + 0.5) * 360 * degree / n;
        const er = vector(cos(ang), sin(ang));
        const et = vector(-sin(ang), cos(ang));
        const u1 = (er + et) / sqrt(2);
        const u2 = (er - et) / sqrt(2);
        const heel = er * (ro - embed);

        const pts = [
                heel,
                heel + u1 * a,
                heel + u1 * a + u2 * s,
                heel + u1 * s + u2 * s,
                heel + u1 * s + u2 * a,
                heel + u2 * a
            ];

        for (var k = 0; k < 6; k += 1)
        {
            skLineSegment(sketch, "leg" ~ i ~ "seg" ~ k, {
                        "start" : pts[k],
                        "end" : pts[(k + 1) % 6]
                    });
        }
    }
    skSolve(sketch);

    // От плоскости установки вверх до нахлёста на обечайку (сливаются с корпусом)
    extrude(context, id + "extrude", {
                "entities" : qSketchRegion(sketchId),
                "endBound" : BoundingType.BLIND,
                "depth" : definition.legHeight + weld,
                "oppositeDirection" : false,
                "operationType" : NewBodyOperationType.ADD,
                "defaultScope" : true
            });

    opDeleteBodies(context, id + "deleteSketch", { "entities" : qCreatedBy(sketchId, EntityType.BODY) });
}

// =====================================================================================
// 5. Опорная решётка сорбента — отдельное тело
// =====================================================================================
function buildSupportGrid(context is Context, id is Id, base is Plane, definition is map)
{
    const ri = definition.vesselOD / 2 - definition.wallThickness;
    const z = definition.legHeight + definition.wallThickness + definition.gridElevation;

    extrudeCirclesOnPlane(context, id + "disk", elevationPlane(base, z), [vector(0, 0) * meter],
        ri - GRID_CLEARANCE, definition.gridThickness, false, NewBodyOperationType.NEW);
}
