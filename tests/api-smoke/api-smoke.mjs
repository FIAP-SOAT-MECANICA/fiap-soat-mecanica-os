const baseUrl = process.env.API_BASE_URL ?? "http://localhost:8080";

const credentials = {
  atendente: { email: `atendente.smoke.${Date.now()}@mecanica.test`, senha: "Senha@123" },
  mecanico: { email: `mecanico.smoke.${Date.now()}@mecanica.test`, senha: "Senha@123" },
  almoxarife: { email: `almoxarife.smoke.${Date.now()}@mecanica.test`, senha: "Senha@123" },
};

const v9 = {
  atendenteId: "11111111-1111-1111-1111-111111111111",
  clientePfId: "33333333-3333-3333-3333-333333333333",
  clientePfCpf: "52998224725",
  veiculoId: "44444444-4444-4444-4444-444444444444",
  servicoId: "77777777-7777-7777-7777-777777777777",
  ordemServicoId: "66666666-6666-6666-6666-666666666666",
};

const missingUuid = "00000000-0000-0000-0000-000000000001";
const runId = Date.now();
const failures = [];

const state = {
  tokenAtendente: null,
  tokenMecanico: null,
  tokenAlmoxarife: null,
  usuarioAtendente: null,
  usuarioMecanico: null,
  usuarioAlmoxarife: null,
  clientePf: null,
  clientePj: null,
  veiculo: null,
  servico: null,
  ordemServico: null,
};

function pass(name) {
  console.log(`✅ PASS - ${name}`);
}

function fail(name, expected, received, body) {
  const renderedBody = typeof body === "string" ? body : JSON.stringify(body);
  const entry = { name, expected, received, body: renderedBody };
  failures.push(entry);
  console.log(`❌ FAIL - ${name} - esperado ${expected} vs recebido ${received} - ${renderedBody}`);
}

async function parseBody(response) {
  const text = await response.text();
  if (!text) return null;

  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

async function request(method, path, { token, body } = {}) {
  const headers = { Accept: "application/json" };

  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";

  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  return {
    status: response.status,
    body: await parseBody(response),
  };
}

function expectStatus(name, result, expectedStatuses) {
  const expected = Array.isArray(expectedStatuses) ? expectedStatuses : [expectedStatuses];

  if (expected.includes(result.status)) {
    pass(name);
    return true;
  }

  fail(name, expected.join(" ou "), result.status, result.body);
  return false;
}

async function test(name, expectedStatuses, operation) {
  try {
    const result = await operation();
    expectStatus(name, result, expectedStatuses);
    return result;
  } catch (error) {
    fail(name, "requisicao executada", "erro local", error.message);
    return { status: 0, body: null };
  }
}

function assertHasId(name, resource) {
  if (resource?.id) {
    pass(name);
    return true;
  }

  fail(name, "body.id preenchido", "body.id ausente", resource);
  return false;
}

function assertPresent(name, value) {
  if (value !== null && value !== undefined && value !== "") {
    pass(name);
    return true;
  }

  fail(name, "valor preenchido", value, { value });
  return false;
}

function assertEquals(name, actual, expected) {
  if (actual === expected) {
    pass(name);
    return true;
  }

  fail(name, expected, actual, { actual, expected });
  return false;
}

async function login(label, credential) {
  const result = await request("POST", "/auth/login", { body: credential });
  if (!expectStatus(`AUTH - login ${label}`, result, 200)) {
    throw new Error(`Login falhou para ${label}. Verifique se a aplicacao esta rodando e se a V9 foi aplicada.`);
  }

  if (!result.body?.jwt) {
    fail(`AUTH - token ${label}`, "body.token preenchido", "body.token ausente", result.body);
    throw new Error(`Token ausente para ${label}.`);
  }

  return result.body.jwt;
}

function onlyDigits(value) {
  return String(value).replace(/\D/g, "");
}

function cpfFromSeed(seed) {
  const base = onlyDigits(seed).padStart(9, "0").slice(-9);
  const firstDigit = cpfDigit(base, 10);
  const secondDigit = cpfDigit(`${base}${firstDigit}`, 11);
  return `${base}${firstDigit}${secondDigit}`;
}

function cpfDigit(value, weightStart) {
  const sum = value
    .split("")
    .reduce((total, digit, index) => total + Number(digit) * (weightStart - index), 0);
  const digit = 11 - (sum % 11);
  return digit >= 10 ? 0 : digit;
}

function cnpjFromSeed(seed) {
  const base = onlyDigits(seed).padStart(12, "0").slice(-12);
  const firstDigit = cnpjDigit(base, [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
  const secondDigit = cnpjDigit(`${base}${firstDigit}`, [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
  return `${base}${firstDigit}${secondDigit}`;
}

function cnpjDigit(value, weights) {
  const sum = value
    .split("")
    .reduce((total, character, index) => total + Number(character) * weights[index], 0);
  const mod = sum % 11;
  return mod < 2 ? 0 : 11 - mod;
}

function uniquePlate(offset = 0) {
  const value = (runId + offset) % 10000;
  return `TST${String(value).padStart(4, "0")}`;
}

async function criarServicoSmoke(nomeSufixo) {
  const result = await test(`MECANICO - POST /servicos para ${nomeSufixo}`, 200, () =>
    request("POST", "/servicos", {
      token: state.tokenMecanico,
      body: {
        nome: `Servico ${nomeSufixo} ${runId}`,
        descricao: `Servico ${nomeSufixo} criado pela suite smoke`,
      },
    }),
  );
  assertHasId(`MECANICO - servico ${nomeSufixo} criado com id`, result.body);
  return result.body;
}

async function criarOrdemSmoke(nomeSufixo) {
  const result = await test(`MECANICO - POST /ordem-servicos para ${nomeSufixo}`, 200, () =>
    request("POST", "/ordem-servicos", {
      token: state.tokenMecanico,
      body: {
        observacao: `OS ${nomeSufixo} Smoke ${runId}`,
        veiculoId: state.veiculo?.id ?? v9.veiculoId,
      },
    }),
  );
  assertHasId(`MECANICO - OS ${nomeSufixo} criada com id`, result.body);
  return result.body;
}

async function runPublicTests() {
  const atendente = await test("PUBLIC - POST /usuarios ATENDENTE", 200, () =>
    request("POST", "/usuarios", {
      body: {
        nome: `Atendente Smoke ${runId}`,
        email: credentials.atendente.email,
        senha: credentials.atendente.senha,
        cargoEnum: "ATENDENTE",
      },
    }),
  );
  state.usuarioAtendente = atendente.body;
  assertHasId("PUBLIC - usuario ATENDENTE criado com id", state.usuarioAtendente);

  const mecanico = await test("PUBLIC - POST /usuarios MECANICO", 200, () =>
    request("POST", "/usuarios", {
      body: {
        nome: `Mecanico Smoke ${runId}`,
        email: credentials.mecanico.email,
        senha: credentials.mecanico.senha,
        cargoEnum: "MECANICO",
      },
    }),
  );
  state.usuarioMecanico = mecanico.body;
  assertHasId("PUBLIC - usuario MECANICO criado com id", state.usuarioMecanico);

  const almoxarife = await test("PUBLIC - POST /usuarios ALMOXARIFE", 200, () =>
    request("POST", "/usuarios", {
      body: {
        nome: `Almoxarife Smoke ${runId}`,
        email: credentials.almoxarife.email,
        senha: credentials.almoxarife.senha,
        cargoEnum: "ALMOXARIFE",
      },
    }),
  );
  state.usuarioAlmoxarife = almoxarife.body;
  assertHasId("PUBLIC - usuario ALMOXARIFE criado com id", state.usuarioAlmoxarife);
}

async function runAtendenteTests() {
  const token = state.tokenAtendente;
  const cpf = cpfFromSeed(runId);
  const cnpj = cnpjFromSeed(runId + 1000000);
  const placa = uniquePlate(1);

  const criarPf = await test("ATENDENTE - POST /clientes PF valido", 200, () =>
    request("POST", "/clientes", {
      token,
      body: {
        nome: `Cliente PF Smoke ${runId}`,
        cpf,
        cnpj: null,
        email: `cliente.pf.${runId}@mecanica.test`,
        telefone: "11987654321",
      },
    }),
  );
  state.clientePf = criarPf.body;
  assertHasId("ATENDENTE - cliente PF criado com id", state.clientePf);

  const criarPj = await test("ATENDENTE - POST /clientes PJ valido", 200, () =>
    request("POST", "/clientes", {
      token,
      body: {
        nome: `Cliente PJ Smoke ${runId}`,
        cpf: null,
        cnpj,
        email: `cliente.pj.${runId}@mecanica.test`,
        telefone: "1133334444",
      },
    }),
  );
  state.clientePj = criarPj.body;
  assertHasId("ATENDENTE - cliente PJ criado com id", state.clientePj);

  await test("ATENDENTE - PATCH /clientes/{id}", 200, () =>
    request("PATCH", `/clientes/${state.clientePf?.id}`, {
      token,
      body: {
        nome: `Cliente PF Smoke Alterado ${runId}`,
        telefone: "11999998888",
      },
    }),
  );

  await test("ATENDENTE - GET /clientes/por-cpf/{cpf}", 200, () =>
    request("GET", `/clientes/por-cpf/${cpf}`, { token }),
  );

  await test("ATENDENTE - GET /clientes/por-cnpj/{cnpj}", 200, () =>
    request("GET", `/clientes/por-cnpj/${cnpj}`, { token }),
  );

  await test("ATENDENTE - GET /clientes/por-usuario/{usuarioId}", 200, () =>
    request("GET", `/clientes/por-usuario/${state.usuarioAtendente?.id ?? v9.atendenteId}`, { token }),
  );

  const criarVeiculo = await test("ATENDENTE - POST /veiculos valido", 200, () =>
    request("POST", "/veiculos", {
      token,
      body: {
        placa,
        marca: "Toyota",
        modelo: "Corolla Smoke",
        ano: "2021",
        quantidadeEixos: 2,
        clienteId: state.clientePf?.id,
      },
    }),
  );
  state.veiculo = criarVeiculo.body;
  assertHasId("ATENDENTE - veiculo criado com id", state.veiculo);

  await test("ATENDENTE - GET /veiculos/{id}", 200, () =>
    request("GET", `/veiculos/${state.veiculo?.id}`, { token }),
  );

  await test("ATENDENTE - GET /veiculos/por-placa/{placa}", 200, () =>
    request("GET", `/veiculos/por-placa/${placa}`, { token }),
  );

  await test("ATENDENTE - GET /veiculos/por-cliente/{clienteId}", 200, () =>
    request("GET", `/veiculos/por-cliente/${state.clientePf?.id}`, { token }),
  );
}

async function runMecanicoSetupTests() {
  const token = state.tokenMecanico;

  const criarServico = await test("MECANICO - POST /servicos", 200, () =>
    request("POST", "/servicos", {
      token,
      body: {
        nome: `Servico Smoke ${runId}`,
        descricao: "Servico criado pela suite smoke",
      },
    }),
  );
  state.servico = criarServico.body;
  assertHasId("MECANICO - servico criado com id", state.servico);

  const alterarServico = await test("MECANICO - PUT /servicos/{id}", 200, () =>
    request("PUT", `/servicos/${state.servico?.id}`, {
      token,
      body: {
        nome: `Servico Smoke Alterado ${runId}`,
        descricao: "Servico alterado pela suite smoke",
      },
    }),
  );
  state.servico = alterarServico.body;

  const criarOrdem = await test("MECANICO - POST /ordem-servicos", 200, () =>
    request("POST", "/ordem-servicos", {
      token,
      body: {
        observacao: `OS Smoke ${runId}`,
        veiculoId: state.veiculo?.id ?? v9.veiculoId,
      },
    }),
  );
  state.ordemServico = criarOrdem.body;
  assertHasId("MECANICO - ordem de servico criada com id", state.ordemServico);
  assertEquals("MECANICO - OS criada como RECEBIDA", state.ordemServico?.situacao, "RECEBIDA");
  assertEquals("MECANICO - OS nao expoe valorTotal (dono do valor e o Billing)", "valorTotal" in (state.ordemServico ?? {}), false);
  assertEquals("MECANICO - OS nao expoe pago (dono do pagamento e o Billing)", "pago" in (state.ordemServico ?? {}), false);

  const buscarOrdem = await test("MECANICO - GET /ordem-servicos/{id}", 200, () =>
    request("GET", `/ordem-servicos/${state.ordemServico?.id}`, { token }),
  );
  state.ordemServico = buscarOrdem.body;

  await test("MECANICO - GET /ordem-servicos/veiculo/placa/{placa}", 200, () =>
    request("GET", `/ordem-servicos/veiculo/placa/${state.veiculo?.placa ?? "ABC1D23"}`, { token })
  );
}

async function runRemainingEndpointTests() {
  const tokenMecanico = state.tokenMecanico;

  const servicoStatus = await criarServicoSmoke("Status");

  const servicoInativo = await test("MECANICO - PATCH /servicos/{id}/inativar", 200, () =>
    request("PATCH", `/servicos/${servicoStatus?.id}/inativar`, { token: tokenMecanico }),
  );
  assertEquals("MECANICO - inativar servico altera status para INATIVO", servicoInativo.body?.statusRecursoEnum, "INATIVO");

  const servicoAtivo = await test("MECANICO - PATCH /servicos/{id}/ativar", 200, () =>
    request("PATCH", `/servicos/${servicoStatus?.id}/ativar`, { token: tokenMecanico }),
  );
  assertEquals("MECANICO - ativar servico altera status para ATIVO", servicoAtivo.body?.statusRecursoEnum, "ATIVO");

  const osCancelar = await criarOrdemSmoke("Cancelar");

  // Sem rotas REST de transicao, a OS fica RECEBIDA; a saga (Fase 6) e quem leva a AGUARDANDO_APROVACAO.
  await test("MECANICO - PATCH /ordem-servicos/{id}/cancelar em OS RECEBIDA deve retornar 422", 422, () =>
    request("PATCH", `/ordem-servicos/${osCancelar?.id}/cancelar`, { token: tokenMecanico }),
  );
}

async function runSecurityTests() {
  await test("SECURITY - endpoint protegido sem token deve retornar 401 ou 403", [401, 403], () =>
    request("GET", `/ordem-servicos/${v9.ordemServicoId}`),
  );

  await test("SECURITY - /clientes com MECANICO deve retornar 403", 403, () =>
    request("GET", `/clientes/por-cpf/${v9.clientePfCpf}`, { token: state.tokenMecanico }),
  );

  await test("SECURITY - /veiculos com MECANICO deve retornar 403", 403, () =>
    request("GET", `/veiculos/${v9.veiculoId}`, { token: state.tokenMecanico }),
  );

  await test("SECURITY - /servicos com ATENDENTE deve retornar 403", 403, () =>
    request("POST", "/servicos", {
      token: state.tokenAtendente,
      body: {
        nome: `Servico Bloqueado ${runId}`,
        descricao: "Nao deve ser criado",
      },
    }),
  );

  await test("SECURITY - /ordem-servicos com ATENDENTE deve retornar 403", 403, () =>
    request("POST", "/ordem-servicos", {
      token: state.tokenAtendente,
      body: {
        observacao: "Nao deve ser criada",
        veiculoId: v9.veiculoId,
      },
    }),
  );


  for (const rota of ["aprovar-orcamento", "recusar-orcamento"]) {
    await test(`SECURITY - PATCH /ordem-servicos/{id}/${rota} removida nao e mais publica`, [401, 403], () =>
      request("PATCH", `/ordem-servicos/${v9.ordemServicoId}/${rota}`),
    );
  }
}

async function runValidationTests() {
  await test("VALIDATION - POST /veiculos com placa invalida deve retornar 400 ou 422", [400, 422], () =>
    request("POST", "/veiculos", {
      token: state.tokenAtendente,
      body: {
        placa: "INVALIDA",
        marca: "Toyota",
        modelo: "Corolla",
        ano: "2021",
        quantidadeEixos: 2,
        clienteId: state.clientePf?.id ?? v9.clientePfId,
      },
    }),
  );

  await test("VALIDATION - POST /veiculos com placa duplicada deve retornar 400 ou 422", [400, 422], () =>
    request("POST", "/veiculos", {
      token: state.tokenAtendente,
      body: {
        placa: state.veiculo?.placa ?? "ABC1D23",
        marca: "Toyota",
        modelo: "Corolla",
        ano: "2021",
        quantidadeEixos: 2,
        clienteId: state.clientePf?.id ?? v9.clientePfId,
      },
    }),
  );

  await test("VALIDATION - POST /clientes sem CPF e sem CNPJ deve retornar 400 ou 422", [400, 422], () =>
    request("POST", "/clientes", {
      token: state.tokenAtendente,
      body: {
        nome: `Cliente Documento Ausente ${runId}`,
        cpf: null,
        cnpj: null,
        email: `cliente.sem-doc.${runId}@mecanica.test`,
        telefone: "11987654321",
      },
    }),
  );

  await test("VALIDATION - POST /clientes com CPF e CNPJ juntos deve retornar 400 ou 422", [400, 422], () =>
    request("POST", "/clientes", {
      token: state.tokenAtendente,
      body: {
        nome: `Cliente Documento Duplicado ${runId}`,
        cpf: cpfFromSeed(runId + 2000000),
        cnpj: cnpjFromSeed(runId + 3000000),
        email: `cliente.docs.${runId}@mecanica.test`,
        telefone: "11987654321",
      },
    }),
  );

  await test("VALIDATION - POST /servicos com nome duplicado deve retornar 400 ou 422", [400, 422], () =>
    request("POST", "/servicos", {
      token: state.tokenMecanico,
      body: {
        nome: `Servico Smoke Alterado ${runId}`,
        descricao: "Duplicado pela suite smoke",
      },
    }),
  );

  await test("VALIDATION - GET /veiculos/{id} com UUID inexistente deve retornar 404", 404, () =>
    request("GET", `/veiculos/${missingUuid}`, { token: state.tokenAtendente }),
  );

  await test("VALIDATION - GET /ordem-servicos/{id} com UUID inexistente deve retornar 404", 404, () =>
    request("GET", `/ordem-servicos/${missingUuid}`, { token: state.tokenMecanico }),
  );
}

function printSummary() {
  if (failures.length === 0) {
    console.log("\nResultado: todos os testes passaram.");
    return;
  }

  console.log(`\nResultado: ${failures.length} falha(s).`);
  console.log("Bugs/achados funcionais:");
  for (const failure of failures) {
    console.log(`- ${failure.name}: esperado ${failure.expected}, recebido ${failure.received}`);
  }
}

async function main() {
  console.log(`API smoke tests - ${baseUrl}`);
  console.log(`Run ID: ${runId}`);

  await runPublicTests();

  state.tokenAtendente = await login("ATENDENTE", credentials.atendente);
  state.tokenMecanico = await login("MECANICO", credentials.mecanico);
  state.tokenAlmoxarife = await login("ALMOXARIFE", credentials.almoxarife);

  await runAtendenteTests();
  await runMecanicoSetupTests();
  await runRemainingEndpointTests();
  await runSecurityTests();
  await runValidationTests();

  printSummary();
  process.exitCode = failures.length > 0 ? 1 : 0;
}

main().catch((error) => {
  fail("execucao da suite", "concluir execucao", "erro fatal", error.message);
  printSummary();
  process.exitCode = 1;
});
