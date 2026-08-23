package de.fricke.pzstory;

import java.util.HashMap;
import java.util.Map;

/**
 * Script id -> the name a person would actually say.
 *
 * The engine only gives us the file name of a vehicle: getScriptName() on the
 * bus outside the house returns "Base.fhqB10M_Riv", which is not something a
 * survivor thinks, and a page written from it either says nothing about the
 * vehicle or invents a make. The key item happens to carry the real name
 * ("Vehicle Key - Volvo B10M") but only if he is holding the key.
 *
 * GENERATED from Elkin's resources/cars.txt - vanilla plus the workshop packs
 * on this install. An unknown id is not an error: it falls back to the script
 * name with Base. stripped, which is ugly but never wrong.
 */
public final class Vehicles {

    private Vehicles() {}

    private static final Map<String, String> NAMES = new HashMap<>(311);

    private static void p(String script, String name) {
        NAMES.put(script.toLowerCase(), name);
    }

    static {
        p("Base.51chevy3100", "Chevrolet 3100");
        p("Base.51chevy3100old", "Chevrolet 3100 Old");
        p("Base.64mustang", "Ford Mustang");
        p("Base.65gto", "Pontiac GTO");
        p("Base.68elcamino", "Chevrolet El Camino 1968");
        p("Base.68wildcat", "Buick Wildcat");
        p("Base.68wildcatconvert", "Buick Wildcat Convertible");
        p("Base.69charger", "Dodge Charger");
        p("Base.69chargerdaytona", "Dodge Charger Daytona");
        p("Base.70chevelle", "Chevrolet Chevelle");
        p("Base.70elcamino", "Chevrolet El Camino 1970");
        p("Base.71chevyc10lb", "Chevrolet C10 1971 (LWB)");
        p("Base.71chevyc10sb", "Chevrolet C10 1971 (SWB)");
        p("Base.71chevyc10stepside", "Chevrolet C10 1971 (Stepside)");
        p("Base.71impala", "Chevrolet Impala");
        p("Base.72beetle", "Volkswagon Beetle");
        p("Base.73falcon", "Ford Falcon");
        p("Base.73pinto", "Ford Pinto");
        p("Base.77transam", "Pontiac Trans Am");
        p("Base.79brougham", "Cadillac Brougham");
        p("Base.79datsun280z", "Datsun 280z");
        p("Base.80f350", "Ford F350");
        p("Base.80f350ambulance", "Ford F350 (Ambulance)");
        p("Base.80f350offroad", "Ford F350 (Offroad)");
        p("Base.80f350quad", "Ford F350 (Dually)");
        p("Base.83hilux", "Toyota Hilux");
        p("Base.83hiluxoffroad", "Toyota Hilux Offroad");
        p("Base.85vicranger", "Ford Crown Victoria 1985 (Ranger)");
        p("Base.85vicsed", "Ford Crown Victoria 1985 (LTD)");
        p("Base.85vicsheriff", "Ford Crown Victoria 1985 (Sheriff)");
        p("Base.85vicwag", "Ford Crown Victoria 1985 (Wagon 1)");
        p("Base.85vicwag2", "Ford Crown Victoria 1985 (Wagon 2)");
        p("Base.86bounder", "Fleetwood Bounder");
        p("Base.86econoline", "Ford Econoline");
        p("Base.86econolineambulance", "Ford E350");
        p("Base.86econolineflorist", "Ford Econoline Florist");
        p("Base.86econolinerv", "Ford E350 RV");
        p("Base.86montecarlo", "Chevrolet Monte Carlo");
        p("Base.86yugo", "Zastava Yugo");
        p("Base.87blazer", "Chevrolet Blazer");
        p("Base.87blazeroffroad", "Chevrolet Blazer");
        p("Base.87c10fire", "Chevrolet C10 1987 (Fire)");
        p("Base.87c10lb", "Chevrolet C10 1987 (LWB)");
        p("Base.87c10mccoy", "Chevrolet C10 1987 (McCoy)");
        p("Base.87c10offroadlb", "Chevrolet C10 1987 (Offroad LWB)");
        p("Base.87c10offroadsb", "Chevrolet C10 1987 (Offroad SWB)");
        p("Base.87c10sb", "Chevrolet C10 1987 (SWB)");
        p("Base.87c10utility", "Chevrolet C10 1987 (Utility)");
        p("Base.87caprice", "Chevrolet Caprice");
        p("Base.87capricePD", "Chevrolet Caprice Police");
        p("Base.87suburban", "Chevrolet Suburban");
        p("Base.90corolla", "Toyota Corolla");
        p("Base.90ramlb", "Dodge Ram LWB");
        p("Base.90ramoffroadlb", "Dodge Ram LWB");
        p("Base.90ramoffroadsb", "Dodge Ram SWB");
        p("Base.90ramsb", "Dodge Ram SWB");
        p("Base.91blazerpd", "Chevrolet Blazer");
        p("Base.91celica", "Toyota Celica");
        p("Base.91chevys10", "Chevrolet S10");
        p("Base.91chevys10ext", "Chevrolet S10 (Extended)");
        p("Base.91chevys10offroad", "Chevrolet S10 (Offroad)");
        p("Base.91chevys10offroadext", "Chevrolet S10 (Offroad Extended)");
        p("Base.91crx", "Honda CRX");
        p("Base.91wagoneer", "Jeep Wagoneer");
        p("Base.92crownvic", "Crown Victoria 1992");
        p("Base.92crownvicPD", "Crown Victoria Police 1992");
        p("Base.92wrangler", "Jeep Wrangler");
        p("Base.92wranglerjurassic", "That truck from that movie!");
        p("Base.92wrangleroffroad", "Jeep Wrangler Offroad");
        p("Base.92wranglerranger", "Jeep Wrangler");
        p("Base.93explorer", "Ford Explorer");
        p("Base.93explorerjurassic", "That truck from that movie!");
        p("Base.93jeepcherokee", "Jeep Cherokee");
        p("Base.astrovan", "Chevrolet Astro");
        p("Base.CarLights", "Chevalier Nyala (Ranger)");
        p("Base.CarLightsPolice", "Chevalier Nyala (Police)");
        p("Base.CarLuxury", "Mercia Lang 4000");
        p("Base.CarNormal", "Chevalier Nyala");
        p("Base.CarStationWagon", "Chevalier Cerise Wagon");
        p("Base.CarStationWagon2", "Chevalier Cerise Wagon (Wood Siding)");
        p("Base.CarTaxi", "Chevalier Nyala (Yellow Taxi)");
        p("Base.CarTaxi2", "Chevalier Nyala (Green Taxi)");
        p("Base.chevystepvan", "Chevrolet Step Van");
        p("Base.chevystepvanswat", "Chevrolet SWAT Van");
        p("Base.f700box", "Ford F700 / B700 (Box Truck)");
        p("Base.f700dump", "Ford F700 / B700 (Dump Truck)");
        p("Base.f700flatbed", "Ford F700 / B700 (Flatbed)");
        p("Base.f700propane", "Ford F700 / B700 (Propane)");
        p("Base.fhq250GTO", "Ferrari 250 GTO Series 1");
        p("Base.fhq300ZXZ32", "Nissan 300ZX (Z32)");
        p("Base.fhq300ZXZ32Forza", "Nissan 300ZX (Z32) Rossi Motorsport Custom");
        p("Base.fhq300ZXZ32MC2Police", "Nissan 300ZX (Z32) Tokyo Police Car");
        p("Base.fhq300ZXZ32Plus2", "Nissan 300ZX (Z32) 2+2");
        p("Base.fhq300ZXZ32Slicktop", "Nissan 300ZX (Z32)");
        p("Base.fhq57BelAir", "Chevrolet '57 Bel Air");
        p("Base.fhq57BelAirKSP", "Chevrolet '57 150 KSP");
        p("Base.fhq57BelAirPolice", "Chevrolet '57 Bel Air Police");
        p("Base.fhq57Chev150", "Chevrolet 150");
        p("Base.fhq57Chev1502Door", "Chevrolet 150 2-Door Sedan");
        p("Base.fhq57Chev210", "Chevrolet 210");
        p("Base.fhq57Chev2102Door", "Chevrolet 210 2-Door Sedan");
        p("Base.fhq58Porsche356", "Porsche 356A");
        p("Base.fhq61KarmannGhia", "Volkswagen Type 14 Karmann Ghia");
        p("Base.fhq67Porsche911", "Porsche 911");
        p("Base.fhq67Porsche912", "Porsche 912");
        p("Base.fhq69GTO", "Pontiac '69 GTO");
        p("Base.fhq69OvalChamp", "Hunter Oval Champ '69");
        p("Base.fhq70Challenger", "Dodge '70 Challenger");
        p("Base.fhq70ChallengerRT", "Dodge '70 Challenger R/T");
        p("Base.fhq70ChallengerSF", "Dodge '70 Challenger Detective Special");
        p("Base.fhq70ChallengerShaker", "Dodge '70 Challenger R/T");
        p("Base.fhq70ChallengerTA", "Dodge '70 Challenger T/A");
        p("Base.fhq70ChallengerTARace", "Dodge '70 Challenger Trans-Am Race Car");
        p("Base.fhq70Porsche914", "Porsche 914");
        p("Base.fhq71Cuda", "Plymouth '71 'Cuda");
        p("Base.fhq78Scirocco", "Volkswagen '78 Scirocco (Mk. 1)");
        p("Base.fhq78SciroccoS", "Volkswagen '78 Scirocco S (Mk. 1)");
        p("Base.fhq88Mazda626", "Mazda 626 (GD)");
        p("Base.fhq92Econoline", "Ford Econoline");
        p("Base.fhq92Econoline4Door", "Ford Econoline");
        p("Base.fhq92EconolineAmbulance", "Ford Econoline Ambulance");
        p("Base.fhq92EconolineAmbulanceLightbar", "Ford Econoline Ambulance");
        p("Base.fhq92EconolinePanel", "Ford Econoline Panel Van");
        p("Base.fhq92EconolinePAYDAY", "Suspicious Looking Van");
        p("Base.fhq92EconolineT3Ambulance", "Ford Econoline Type III Ambulance");
        p("Base.fhq92EconolineValkyrie", "Ford Econoline Valkyrie Custom");
        p("Base.fhq92EconolineXL", "Ford Econoline XL");
        p("Base.fhq92EconolineXLPanel", "Ford Econoline XL Panel Van");
        p("Base.fhqAZ1", "Mazda Autozam AZ-1");
        p("Base.fhqB10M", "Volvo B10M");
        p("Base.fhqB10M_Riv", "Volvo B10M");
        p("Base.fhqBeat", "Honda Beat");
        p("Base.fhqBeatMugen", "Honda Mugen Beat");
        p("Base.fhqBeatRace", "Honda Beat Race Car");
        p("Base.fhqBomberBFS", "Bomber BFS");
        p("Base.fhqBronco", "Ford Bronco");
        p("Base.fhqBroncoHalfCab", "Ford Bronco Half-Cab");
        p("Base.fhqBroncoHalfCabOffroad", "Ford Bronco Half-Cab Offroad");
        p("Base.fhqBroncoOffroad", "Ford Bronco Offroad");
        p("Base.fhqCappuccino", "Suzuki Cappuccino");
        p("Base.fhqCara", "Suzuki Cara");
        p("Base.fhqCelicaGT4RC", "Toyota Celica GT-FOUR RC");
        p("Base.fhqChaserJZX90", "Toyota Chaser (X90)");
        p("Base.fhqChili", "Chili");
        p("Base.fhqCountach", "Lamborghini Countach LP400");
        p("Base.fhqCrestaJZX90", "Toyota Cresta (X90)");
        p("Base.fhqDeltaEvo", "Lancia Delta Integrale Evoluzione");
        p("Base.fhqDeltaEvoRally", "Lancia Delta Integrale Evoluzione Rally Car");
        p("Base.fhqDiablo", "Lamborghini Diablo");
        p("Base.fhqDiabloInterceptor", "Lamborghini Diablo Police Interceptor");
        p("Base.fhqDiabloPolice", "Lamborghini Diablo Police Car");
        p("Base.fhqDiabloSE30", "Lamborghini Diablo SE30");
        p("Base.fhqDiabloStrosek", "Strosek Lamborghini Diablo");
        p("Base.fhqE36318iCoupe", "BMW 318i (E36)");
        p("Base.fhqE36318iSedan", "BMW 318i Sedan (E36)");
        p("Base.fhqE36Lime", "The Lime Mobile");
        p("Base.fhqE36M3Coupe", "BMW M3 (E36)");
        p("Base.fhqE36M3GTR", "BMW M3 GTR (E36)");
        p("Base.fhqE36M3GTRMW", "BMW M3 GTR (E36) 'Most Wanted'");
        p("Base.fhqE36M3Sedan", "BMW M3 Sedan (E36)");
        p("Base.fhqFBMustangGT", "Ford Fox Body Mustang GT");
        p("Base.fhqFBMustangHatchSSP", "Ford Fox Body Mustang SSP");
        p("Base.fhqFBMustangHatchSSPLightbar", "Ford Fox Body Mustang SSP");
        p("Base.fhqFBMustangLX", "Ford Fox Body Mustang");
        p("Base.fhqFBMustangLX50", "Ford Fox Body Mustang 5.0L");
        p("Base.fhqFBMustangLX50Custom", "Ford Fox Body Mustang");
        p("Base.fhqFBMustangLXCoupe", "Ford Fox Body Mustang");
        p("Base.fhqFBMustangLXCoupe50", "Ford Fox Body Mustang 5.0L");
        p("Base.fhqFBMustangSAACMkII", "SAAC Mustang Mk. II");
        p("Base.fhqFBMustangSSP", "Ford Fox Body Mustang SSP");
        p("Base.fhqFBMustangSSPLightbar", "Ford Fox Body Mustang SSP");
        p("Base.fhqFBMustangSVO", "Ford Mustang SVO");
        p("Base.fhqFBMustangSVO2", "Ford Mustang SVO");
        p("Base.fhqFBMustangSVT", "Ford Fox Body Mustang SVT Cobra");
        p("Base.fhqFiero", "Pontiac Fiero 2M4");
        p("Base.fhqFieroGenLee", "The Iron Duke of Hazzard");
        p("Base.fhqFieroGT", "Pontiac Fiero GT");
        p("Base.fhqFieroGTFastback", "Pontiac Fiero GT Fastback");
        p("Base.fhqFieroIndy", "Pontiac Indy Fiero");
        p("Base.fhqFieroJalapeno", "PISA Jalapeño Fiero");
        p("Base.fhqFieroPace", "Pontiac Fiero Pace Car");
        p("Base.fhqG4Coupe", "Ginetta G4 Coupe");
        p("Base.fhqGT40", "Chevrolet S10 (Ford GT40 Mk. I)");
        p("Base.fhqGT40Gulf", "Chevrolet S10 (Ford GT40 Mk. I Race Car)");
        p("Base.fhqGT40LM", "Chevrolet S10 (Ford GT40 Mk. I LM Edition)");
        p("Base.fhqGT40MkII", "Ford GT40 Mk. II");
        p("Base.fhqGT40MkIIRace", "Ford GT40 Mk. II '66 Le Mans Race Car");
        p("Base.fhqHQPanelVan", "Holden Panel Van (HQ)");
        p("Base.fhqHQSandman", "Holden Sandman (HQ)");
        p("Base.fhqHQSandmanUte", "Holden Sandman Ute (HQ)");
        p("Base.fhqHQUte", "Holden Ute (HQ)");
        p("Base.fhqImpreza", "Subaru Impreza (GC)");
        p("Base.fhqImprezaCoupe", "Subaru Impreza (GM)");
        p("Base.fhqImprezaRally", "Subaru WRC 555 Impreza Prototype");
        p("Base.fhqImprezaWag", "Subaru Impreza (GF)");
        p("Base.fhqImprezaWRX", "Subaru Impreza WRX (GC)");
        p("Base.fhqImprezaWRXWag", "Subaru Impreza WRX (GF)");
        p("Base.fhqImprezaWRXWagOffroad", "Subaru Impreza WRX Offroad (GF)");
        p("Base.fhqLeCar", "Renault Le Car");
        p("Base.fhqLeCarPolice", "Renault Le Car Police");
        p("Base.fhqLexusLS400", "Lexus LS400");
        p("Base.fhqLexusSC300", "Lexus SC300");
        p("Base.fhqLexusSC400", "Lexus SC400");
        p("Base.fhqLM002", "Lamborghini LM002");
        p("Base.fhqLM002Estate", "Lamborghini LM002 Estate");
        p("Base.fhqM20b", "Tommykaira M20b");
        p("Base.fhqM715CivMZ", "Kaiser Jeep M715");
        p("Base.fhqM715hardtopCivMZ", "Kaiser Jeep M715");
        p("Base.fhqM715hardtopMZ", "Kaiser Jeep M715");
        p("Base.fhqM715MZ", "Kaiser Jeep M715");
        p("Base.fhqMarkIIJZX90", "Toyota Mark II (X90)");
        p("Base.fhqMcLarenF1", "McLaren F1");
        p("Base.fhqMX5NA", "Mazda MX-5 Miata (NA)");
        p("Base.fhqMX5NAHardtop", "Mazda MX-5 Miata (NA)");
        p("Base.fhqMX5NAOffroad", "Mazda MX-5 Miata (NA) Offroad");
        p("Base.fhqMX5NARally", "Mazda MX-5 Miata (NA) Rally");
        p("Base.fhqMX5NAWink", "Mazda MX-5 Miata (NA)");
        p("Base.fhqNSX", "Acura NSX (NA1)");
        p("Base.fhqonevia", "Nissan 240SX Coupe (S13)");
        p("Base.fhqoneviadrift", "Nissan 240SX Coupe (S13) Drift Custom");
        p("Base.fhqoneviaPreFL", "Nissan 240SX Coupe (S13)");
        p("Base.fhqoneviaS", "Nissan 240SX Coupe (S13) Custom");
        p("Base.fhqPrevia", "Toyota Previa");
        p("Base.fhqPreviaAllTrac", "Toyota Previa All-Trac");
        p("Base.fhqPreviaOffroad", "Toyota Previa Offroad");
        p("Base.fhqR32GTR", "Nissan Skyline GTR (R32)");
        p("Base.fhqR32GTRN1", "Nissan Skyline GTR (R32) N1");
        p("Base.fhqR32GTRPace", "Nissan Skyline GTR (R32) Pace Car");
        p("Base.fhqR32GTRTommy", "Tommykaira R (R32)");
        p("Base.fhqR32GTRTouring", "Nissan Skyline GTR (R32) Group A");
        p("Base.fhqR32GTS", "Nissan Skyline GTS (R32)");
        p("Base.fhqR32Sedan", "Nissan Skyline Sedan (R32)");
        p("Base.fhqRobinMk2", "Reliant Robin Mk. II");
        p("Base.fhqRobinMk2Stabilisers", "Reliant Robin Mk. II");
        p("Base.fhqRollbinMk2", "Unstable Reliant Robin Mk. II");
        p("Base.fhqRollbinMk2Stabilisers", "Stabilized Reliant Robin Mk. II");
        p("Base.fhqSidekick", "Suzuki Sidekick");
        p("Base.fhqSidekickHardtop", "Suzuki Sidekick");
        p("Base.fhqSidekickLWB", "Suzuki Sidekick 4-Door");
        p("Base.fhqSidekickLWBRanger", "Suzuki Sidekick 4-Door Ranger");
        p("Base.fhqStratos", "Lancia Stratos");
        p("Base.fhqStratosRally", "Lancia Stratos Rally Car");
        p("Base.fhqSupraMkIV", "Toyota Supra (Mk. IV A80)");
        p("Base.fhqSupraMkIVOWR", "Toyota Supra (Mk. IV A80) OW Racing");
        p("Base.fhqSupraMkIVOWROOTHST", "Toyota Supra (Mk. IV A80) OOTHST Racing");
        p("Base.fhqSupraMkIVSmooth", "Toyota Supra (Mk. IV A80)");
        p("Base.fhqVWT2T1", "Volkswagen Type 2 Transporter (T1)");
        p("Base.fhqVWT2T1CrewCab", "Volkswagen Type 2 Pickup (T1)");
        p("Base.fhqVWT2T1Livery", "Volkswagen Type 2 Transporter (T1)");
        p("Base.fhqVWT2T1Truck", "Volkswagen Type 2 Pickup (T1)");
        p("Base.fhqVWT2T1Van", "Volkswagen Type 2 Transporter (T1)");
        p("Base.fhqZeroR", "HKS Zero-R");
        p("Base.firepumper", "Fire Engine");
        p("Base.generallee", "The General Lee");
        p("Base.generalmeh", "The General Meh");
        p("Base.hmmwvht", "Trailer (M1025)");
        p("Base.hmmwvtr", "Trailer (M1069)");
        p("Base.isuzubox", "Isuzu N5 (Normal)");
        p("Base.isuzuboxelec", "Isuzu N5 (Electric)");
        p("Base.isuzuboxfood", "Isuzu N5 (Food)");
        p("Base.isuzuboxmccoy", "Isuzu N5 (McCoy)");
        p("Base.m151canvas", "Trailer (M151A2)");
        p("Base.m35a2bed", "Trailer (M35A2)");
        p("Base.m35a2covered", "Trailer (M35A2)");
        p("Base.m35a2fuel", "Trailer (M49A2C)");
        p("Base.ModernCar", "Dash Elite");
        p("Base.ModernCar02", "Chevalier Primani");
        p("Base.moveurself", "Move Urself Box Truck");
        p("Base.OffRoad", "Dash Rancher");
        p("Base.PickUpTruck", "Chevalier D6");
        p("Base.PickUpTruckLights", "Chevalier D6 (Fossoil/Ranger)");
        p("Base.PickUpTruckLightsFire", "Chevalier D6 (Fire)");
        p("Base.PickUpTruckMccoy", "Chevalier D6 (McCoy)");
        p("Base.PickUpVan", "Dash Bulldriver");
        p("Base.PickUpVanLights", "Dash Bulldriver (Fossoil/Ranger)");
        p("Base.PickUpVanLightsFire", "Dash Bulldriver (Fire)");
        p("Base.PickUpVanLightsPolice", "Dash Bulldriver (Police)");
        p("Base.PickUpVanMccoy", "Dash Bulldriver (McCoy)");
        p("Base.pursuitspecial", "Pursuit Special");
        p("Base.schoolbus", "Ford F700 / B700 (School Bus)");
        p("Base.schoolbusshort", "Ford F700 / B700 (School Bus Short)");
        p("Base.SmallCar", "Chevalier Dart");
        p("Base.SmallCar02", "Masterson Horizon");
        p("Base.SportsCar", "Chevalier Cossette");
        p("Base.StepVan", "Step Van");
        p("Base.StepVan_Heralds", "Step Van (Heralds)");
        p("Base.StepVan_Scarlet", "Step Van (Scarlet)");
        p("Base.StepVanManil", "Step Van (Mail)");
        p("Base.SUV", "Dash Bulldriver (Franklin All-Terrain)");
        p("Base.tractorford7810", "Ford 7810");
        p("Base.Trailer", "Trailer");
        p("Base.Trailer51chevy", "Chevrolet Bed");
        p("Base.TrailerAdvert", "Trailer (Advert)");
        p("Base.Trailercamperscamp", "Camper Trailer");
        p("Base.TrailerCover", "Trailer (Covered)");
        p("Base.Trailerfuelmedium", "Medium Fuel Trailer");
        p("Base.Trailerfuelsmall", "Small Fuel Trailer");
        p("Base.Trailermovingbig", "Move Urself 7x16");
        p("Base.Trailermovingmedium", "Move Urself 5x8");
        p("Base.Van", "Franklin Valuline");
        p("Base.Van_KnoxDisti", "Franklin Valuline (Knox Distillery)");
        p("Base.Van_LectroMax", "Franklin Valuline (Lectro Max)");
        p("Base.Van_MassGenFac", "Franklin Valuline (Mass GenFac)");
        p("Base.Van_Transit", "Franklin Valuline (Transit)");
        p("Base.VanAmbulance", "Franklin Valuline (Ambulance)");
        p("Base.VanRadio", "Franklin Valuline (Radio Van)");
        p("Base.VanRadio_3N", "Franklin Valuline (3N Radio Van)");
        p("Base.VanSeats", "Franklin Valuline (6-Seater)");
        p("Base.VanSpecial", "Franklin Valuline (Fossoil)");
        p("Base.VanSpiffo", "Franklin Valuline (Spiffo's)");
        p("Base.volvo244", "Volvo 244");
    }

    /** @return a readable make and model, or a cleaned-up script id. */
    public static String name(String script) {
        if (script == null || script.isBlank()) return "";
        String s = script.trim();
        String hit = NAMES.get(s.toLowerCase());
        if (hit != null) return hit;
        if (!s.toLowerCase().startsWith("base.")) {
            hit = NAMES.get("base." + s.toLowerCase());
            if (hit != null) return hit;
        }
        return s.replaceFirst("(?i)^base\\.", "");
    }

    /** True when we actually know this one, rather than echoing an id. */
    public static boolean known(String script) {
        if (script == null) return false;
        String s = script.trim().toLowerCase();
        return NAMES.containsKey(s) || NAMES.containsKey("base." + s);
    }
}
