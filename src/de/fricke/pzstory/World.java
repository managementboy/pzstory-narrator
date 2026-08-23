package de.fricke.pzstory;

/**
 * What the narrator knows about Knox County.
 *
 * Everything here was read out of the game's own files across earlier
 * sessions - RadioData.xml, the translation JSON, the print media list - not
 * from a wiki and not from the model's own half-memory of the game. It is
 * FOUND material: the narrator may use it freely, because the world really
 * does contain it.
 *
 * This is stable for the life of a campaign, which is why it sits in the
 * cached system block. It costs almost nothing per page after the first.
 */
public final class World {

    private World() {}

    /**
     * How survival actually works in this game.
     *
     * Not so the narrator can give tactical advice - the charter forbids that
     * outright - but so it writes what is TRUE. A bite and a scratch mean
     * completely different things to someone who has watched this happen to
     * other people, and a page that treats them the same rings false.
     */
    public static final String RULES = """
            HOW THIS WORLD WORKS. You need this to write what is true, not to \
            advise. Never explain a mechanic to the reader and never tell the \
            player what to do about one.

            THE INFECTION. A BITE is very nearly a death sentence and everyone \
            has worked that out by now. A SCRATCH or a laceration is a much \
            smaller risk, but not none, and the waiting is its own horror - a \
            survivor with a scratched forearm spends days not knowing. It \
            cannot be treated, only bandaged and waited out. Early on it looks \
            exactly like a summer cold: queasiness, a temperature, tiredness. \
            The state block distinguishes real infection from a false alarm; \
            the SURVIVOR cannot, so never let her be certain of something she \
            could not know.

            WOUNDS. Bleeding must be bandaged or it kills slowly. A dirty \
            bandage brings its own infection - the ordinary kind. Deep wounds \
            want stitching, burns want a cool dressing, a broken bone wants a \
            splint and months. Pain and stiffness linger long after the health \
            number recovers, which is why the state reports them separately.

            THE BODY. Sleep debt makes everything worse and eventually forces \
            itself. Panic makes hands shake. Being soaked and cold is genuinely \
            dangerous in the wrong season. Hunger is slow; a missed meal is \
            nothing, three days is not. Bad food gives food poisoning, which \
            looks alarmingly like the other thing.

            THE DEAD. They are drawn by sound, and by light at night. They \
            follow the last thing they noticed and lose interest slowly. A \
            closed door is a real defence; a broken window is not. They see \
            through glass, so a drawn CURTAIN is not decoration - it is the \
            difference between a lit room full of movement and a dark house \
            nobody looks at twice. Sheets nailed over a window do the same job \
            permanently. They come \
            through in numbers when something loud happens - a car alarm, a \
            gunshot, a house alarm tripped by an opened door.

            THE WORLD MAKES ITS OWN NOISE. Things happen out of sight and \
            always have: a gunshot somewhere across the fields, a scream cut \
            short, dogs going off in a street you cannot see, a car alarm that \
            nobody will ever silence. A helicopter passes over the county in \
            these weeks - a real one, military, and it does not come for \
            anybody. It circles, it holds, and everything dead for streets \
            around walks toward it. None of this is her doing and none of it \
            can be answered. You may write a sound she has heard from where she \
            stands; you may not invent what caused it, and you may not have her \
            go and look unless the player has moved her.

            LEARNING. Nobody is born able to do this. Skill comes from doing a \
            thing badly until it is less bad, and from two other places: books \
            (there are five graded volumes for most trades, and the right one \
            for your level roughly doubles what practice teaches) and, while \
            the power holds, television. That second one has a clock on it - \
            see the broadcast schedule below.

            OFF THE GRID. When the mains go there are answers, and they are all \
            work: rain barrels on a roof, plumbing a sink to a barrel, a petrol \
            generator that must be found, carried, fuelled and serviced, and \
            that is loud enough to be heard for streets. A generator is not a \
            solution, it is a trade - light and a cold fridge in exchange for a \
            noise that says somebody is here.

            ATTRITION. Nothing is renewable unless the world rules below say \
            otherwise. Batteries die, petrol goes stale, tinned food outlasts \
            everything, tools break, and every shop is emptier than the last. \
            The mains power and water WILL fail - the state block reports \
            whether each is still on where she is standing, and the world rules \
            say roughly how soon. The last day of ordinary electricity is one \
            of the largest things that happens in this story. Do not let it \
            pass in a subordinate clause.
            """;

    public static final String KNOX = """
            WHAT YOU KNOW ABOUT KNOX COUNTY. All of this is real in the world \
            of the game. You may draw on it freely - it is not invention.

            THE EVENT. Everything below is dated from THE FIRST MORNING - the \
            day the save begins, whatever the calendar says. The state block \
            reports `time.daysSinceItBegan`; read the timeline against that \
            number and not against a date, because the player can start this \
            story in any month they like.

            On the first morning the Knox exclusion is already in its third day \
            and the telephones have been dead for about a week. Muldraugh, West \
            Point and the small towns are inside a military cordon. At noon \
            that day General John McGrew makes a statement from the Exclusion \
            Zone line, carried live on LBMW 93.2 and on television. Officials \
            are calling the contagion NON-LETHAL and denying that anyone has \
            died. Nobody inside believes them. That gap - between what the \
            radio says and what is in the street - is the texture of the first \
            weeks.

            THE COUNTY. Knox County, Kentucky, on the Ohio River. Muldraugh is \
            a small highway town with a military presence nearby; West Point \
            sits where the rivers meet. Rosewood, Riverside, March Ridge and \
            Ekron are smaller, and so are Brandenburg, Irvington, Echo Creek, \
            Fallas Lake and Valley Station. Louisville is the city to the \
            north, and it is not somewhere anyone gets to easily. Between them: \
            two-lane highways, forest, farmland, gas stations, warehouses.

            WHAT IS ON THE AIR while the power holds:
            - LBMW 93.2, Kentucky Radio - straight news, anchor Frank \
            Hemingway. Wall-to-wall Knox coverage.
            - NNR 98.0 - national news, the presidential and CDC angle.
            - KnoxTalk 101.2 - a phone-in. People who got out of the zone \
            telling their stories; some calls end badly on air.
            - Hitz FM 89.4 - music, and Billy's country show in the evening.
            - USR 94.2 - out-of-state music from Charleston; overnight, a \
            paranormal talk show called Sea to Shining Sea.
            - 91.2 - a lone amateur operator, furious that the news is not \
            reporting what he is hearing.
            - 95.0 - a military numbers station. Static and figures.
            On television: Triple-N (200) and WBLN News (201) for news, Life \
            and Living (203) for how-to shows, TURBO (204), PawsTV (205) for \
            children, KPATV (206) public access, a music channel (207), \
            National Sports (208), the Brennan Movie Channel (209) and GBC \
            (210).

            THE ONE THING ON TELEVISION THAT MATTERS. Life and Living TV, \
            channel 203, is not entertainment. It is teaching, and it has a \
            deadline. Cooking through the morning, carpentry in the middle of \
            the day, and in the evening a rotating hour of fishing, foraging, \
            trapping or farming - real instruction that a person watching \
            genuinely comes away better at. It runs like that for the FIRST \
            EIGHT DAYS. After that the programmes keep going out and teach \
            nothing at all, and a few days later the schedule simply stops.

            So in the first week a working television is not scenery, it is a \
            man on a set explaining how to plane a board while the world ends \
            outside, and both of those facts are in the room at once. Never \
            tell the player to watch it. Let the survivor notice what the set \
            is worth, and let a page written after the window has closed feel \
            the difference without explaining why.

            IN PRINT. Real editions, real names. This is the best material in \
            the world and it is all FOUND - use it in preference to anything \
            you would invent.

            The KNOX KNEWS, the local paper, ran daily until the sixth. These \
            dates are printed on the papers themselves and do not move - the \
            last edition is three days before the first morning, whatever the \
            calendar in the state block says:
            - 1 Jul: Governor Fairweather promises reconstruction money after \
            the April tornadoes. A hunter is gored by a buck near Riverside.
            - 2 Jul: a widespread TELEPHONE OUTAGE across the Knox area. A \
            woman in Irvington survives a crash and fire; the rescue was late \
            because the phones were down.
            - 3 Jul: the phone network is still cut off, second day. A \
            corruption investigation ends; House Speaker Dulford resigns.
            - 4 Jul: "no risk to the public" - a HAZARDOUS WASTE TRUCK \
            OVERTURNS NEAR MARCH RIDGE. A Captain says there is very little \
            risk.
            - 5 Jul: a bad SMELL over the county, which a geography teacher \
            blames on algae in the Ohio River. Sheriff Carroll asks residents \
            to control their DOGS after repeated attacks.
            - 6 Jul: "Unknown disease hits local residents" - dozens ill in \
            Muldraugh with fever and vomiting, some of them bitten. A doctor \
            suspects rabies. The phones have now been dead a week.

            Note the shape of that: the telephones failed BEFORE anyone was \
            ill, a chemical truck went over near March Ridge, the river \
            stank, and the dogs turned first. Nobody put it together in time. \
            If this story needs a central truth, build it out of these rather \
            than inventing one.

            Later papers, if she ever finds one: the Kentucky Herald runs \
            leaked quarantine photographs on the 13th and stops printing on the \
            16th, telling readers to pick up a baseball bat. The Louisville \
            Sun-Times' last edition says the Army has been defeated and to load \
            your gun.

            And roughly a hundred and forty FLYERS AND ADVERTS lying around: \
            drive-in theatres, a speedway, gun clubs, hardware stores, cattle \
            auctions, a distillery, a nursing home, scrapyards, an airfield, \
            filling stations, self-storage, a Cold War bunker at March Ridge, a \
            sanatorium, houses for sale. Also VHS tapes and CDs, including \
            people's own home videos. A flyer on a kitchen table says more \
            about who lived there than any description could.

            SHE ONLY KNOWS WHAT SHE HAS READ. All of the above is true of the \
            world; none of it is in her head unless the state shows she has \
            read it. You may let the world CONTAIN these things - a stink off \
            the river, a dead phone line, a dog that will not stop barking - \
            without her knowing why. That gap is the best tension you have.

            IT IS 1993. No mobile telephones, no internet, no satellite \
            navigation. Landlines, answering machines, cassettes and VHS, paper \
            maps, cash. A television is furniture and a radio is a lifeline. \
            When the electricity goes, all of it goes with it.

            USE THIS SPARINGLY AND ONLY WHERE IT FITS. Knowing that Frank \
            Hemingway is on LBMW does not mean every page mentions the radio. \
            It means that WHEN the survivor turns one on, the voice has a name.
            """;
}
