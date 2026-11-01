// Usage: node scripts/local-provider-stub.mjs [port]   (default port 8765, bound to 127.0.0.1)
// A dependency-free stand-in for an OpenAI-compatible server, for local smoke tests of the
// custom endpoint. It serves a model list and a streamed chat completion, and replays one fixed
// six-call teaching sequence chosen from the tool call the client sends back. It answers from a
// script, so it proves request and stream handling, not the quality of any model.
import http from 'node:http';

const DEFAULT_PORT = 8765;
const HOST = '127.0.0.1';
const MODEL = 'stub-teach';

const requestedPort = process.argv[2];
const port = requestedPort === undefined ? DEFAULT_PORT : Number(requestedPort);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.error(`Port must be an integer from 1 to 65535. Got: ${requestedPort}`);
    process.exit(1);
}

const probeArgs = {
    question: 'When you reassign a variable, what happens to the old value?',
    details: '',
    options: [
        { label: 'It is kept', value: 'kept' },
        { label: 'It is replaced', value: 'replaced' },
    ],
    multiSelect: false,
    explanation: "Reassignment replaces the binding's value.",
    correctAnswer: 'replaced',
};

const finishProbeArgs = {
    assessment: {
        strands: [
            {
                strand: 'variable reassignment',
                assessment: 'Knows assignment; unsure whether old values persist.',
            },
        ],
        summary: 'Start from reassignment, then shared references.',
    },
};

const planInitArgs = {
    nodes: [
        {
            id: 'reassign',
            title: 'Reassignment',
            summary: 'A binding can be rebound to a new value.',
        },
    ],
    edges: [],
};

const predictArgs = {
    question: 'If two names refer to the same list, does a change through one show through the other?',
    details: '',
    options: [
        { label: 'Yes', value: 'yes' },
        { label: 'No', value: 'no' },
    ],
    multiSelect: false,
    explanation: 'Both names hold the same reference.',
    correctAnswer: 'yes',
    nodeId: 'reassign',
    type: 'predict',
};

const checkArgs = {
    question: 'After b = a, what does clearing b do to a?',
    details: '',
    options: [
        { label: 'Nothing', value: 'nothing' },
        { label: 'It clears both', value: 'both' },
    ],
    multiSelect: false,
    explanation: 'They share one list object.',
    correctAnswer: 'both',
    nodeId: 'reassign',
    type: 'check',
};

const finishLessonArgs = {
    summary:
        'Covered reassignment and shared references. Still uncertain: when copying is the right fix.',
};

/** The reply for the step that follows each keyed tool call. Every step uses a fresh call id. */
const NEXT_STEP = {
    start: { tool: 'probe', id: 'call_1', args: probeArgs, note: 'probe phase start' },
    probe: { tool: 'finish_probe', id: 'call_2', args: finishProbeArgs, note: 'assessment' },
    finish_probe: { tool: 'plan_init', id: 'call_3', args: planInitArgs, note: 'plan' },
    plan_init: {
        prose: 'Good. Reassignment rebinds a name. Predict what happens when two names share one list.',
        tool: 'quiz',
        id: 'call_4',
        args: predictArgs,
        note: 'predict quiz',
    },
    'quiz:predict': {
        prose: 'Right, both names point at one list object. Now the check.',
        tool: 'quiz',
        id: 'call_5',
        args: checkArgs,
        note: 'check quiz',
    },
    'quiz:check': { tool: 'finish_lesson', id: 'call_6', args: finishLessonArgs, note: 'finish' },
};

/** The reply once the sequence is spent, so a client that keeps asking gets prose and no tool. */
const STALL = { prose: 'Nothing further to add.', tool: null, note: 'stop' };

function record(line) {
    console.log(`${new Date().toISOString()} ${line}`);
}

/** The most recent assistant message that carried a tool call, or null before any call. */
function lastCall(messages) {
    let found = null;
    for (const message of messages) {
        if (message.role !== 'assistant') continue;
        const calls = Array.isArray(message.tool_calls) ? message.tool_calls : [];
        for (const call of calls) {
            const fn = call && call.function ? call.function : null;
            if (!fn || !fn.name) continue;
            found = { name: String(fn.name), args: String(fn.arguments || '') };
        }
    }
    return found;
}

/** The `type` field of a quiz tool call, or '' when its arguments are not readable JSON. */
function quizType(args) {
    try {
        const parsed = JSON.parse(args);
        return typeof parsed.type === 'string' ? parsed.type : '';
    } catch {
        return '';
    }
}

function stepKey(previous) {
    if (!previous) return 'start';
    return previous.name === 'quiz' ? `quiz:${quizType(previous.args) || '?'}` : previous.name;
}

function describe(previous) {
    return previous ? stepKey(previous) : 'none';
}

function chunk(delta, finish, usage) {
    const event = {
        id: 'chatcmpl-1',
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: MODEL,
        choices: [{ index: 0, delta: delta, finish_reason: finish ?? null }],
    };
    if (usage) event.usage = usage;
    return `data: ${JSON.stringify(event)}\n\n`;
}

function stream(res, reply, promptTokens) {
    res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
    });
    res.write(chunk({ role: 'assistant' }, null));
    if (reply.prose) res.write(chunk({ content: reply.prose }, null));
    if (reply.tool) {
        res.write(
            chunk(
                {
                    tool_calls: [
                        {
                            index: 0,
                            id: reply.id,
                            type: 'function',
                            function: { name: reply.tool, arguments: JSON.stringify(reply.args) },
                        },
                    ],
                },
                null
            )
        );
    }
    const completion = 8 + (reply.prose ? reply.prose.length : 0) + (reply.tool ? 40 : 0);
    res.write(
        chunk({}, reply.tool ? 'tool_calls' : 'stop', {
            prompt_tokens: promptTokens,
            completion_tokens: completion,
            total_tokens: promptTokens + completion,
        })
    );
    res.write('data: [DONE]\n\n');
    res.end();
}

function sendJson(res, status, body) {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
}

function readBody(req) {
    return new Promise((resolve, reject) => {
        const parts = [];
        req.on('data', (piece) => parts.push(piece));
        req.on('end', () => resolve(Buffer.concat(parts).toString('utf8')));
        req.on('error', reject);
    });
}

const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${HOST}:${port}`);

    if (req.method === 'GET' && url.pathname === '/v1/models') {
        record(`GET ${url.pathname} 200 models=1`);
        sendJson(res, 200, { object: 'list', data: [{ id: MODEL, object: 'model' }] });
        return;
    }

    if (req.method === 'POST' && url.pathname === '/v1/chat/completions') {
        const raw = await readBody(req);
        let body = null;
        try {
            body = JSON.parse(raw);
        } catch {
            body = null;
        }
        const messages = Array.isArray(body?.messages) ? body.messages : [];
        const previous = lastCall(messages);
        const reply = NEXT_STEP[stepKey(previous)] ?? STALL;
        record(
            `POST ${url.pathname} 200 model=${body?.model ?? '?'} messages=${messages.length} ` +
                `lastTool=${describe(previous)} reply=${reply.tool ?? 'text'} note=${reply.note}`
        );
        stream(res, reply, Math.max(1, Math.round(raw.length / 4)));
        return;
    }

    record(`${req.method} ${url.pathname} 404`);
    sendJson(res, 404, { error: { message: 'not found', type: 'invalid_request_error' } });
});

server.on('error', (error) => {
    console.error(error.code === 'EADDRINUSE' ? `Port ${port} is already in use.` : String(error));
    process.exit(1);
});

server.listen(port, HOST, () => {
    record(`LISTEN http://${HOST}:${port}/v1 ready`);
});
